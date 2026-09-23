import warnings
import os
import uvicorn
import asyncio
import logging
import secrets
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from sqlalchemy.exc import SQLAlchemyError
from typing import Any, Optional

from contextlib import asynccontextmanager

from route.auth import router as auth_router
from route.feedback import router as feedback_router
from route.studies import router as studies_router
from route.services.generate_script import generate_script_with_park, generate_script_without_park
from route.services.get_route import generate_walk_route
from route.models import WalkRouteRequest, WalkRouteResponse, ScriptWithParkRequest, ScriptWithParkResponse
from route.models import ScriptWithoutParkRequest, ScriptWithoutParkResponse, TTSRequest
from database.core import create_configured_database
from route.utils.utils import _ensure_ffmpeg

logger = logging.getLogger(__name__)

MODEL: Optional[Any] = None
MODEL_LOAD_LOCK = asyncio.Lock()
TTS_SEMAPHORE = asyncio.Semaphore(1)
ROUTE_REQUEST_SEMAPHORE = asyncio.Semaphore(4)
SCRIPT_REQUEST_SEMAPHORE = asyncio.Semaphore(2)


def require_app_token(x_app_token: Optional[str] = Header(default=None)) -> None:
    expected = os.getenv("APP_API_TOKEN", "").strip()
    if not expected:
        return
    if x_app_token is None or not secrets.compare_digest(x_app_token, expected):
        raise HTTPException(status_code=401, detail="Invalid application token")


def _load_tts_model_sync():
    # Keep TTS dependencies and ffmpeg out of the route-only startup path.
    import torch
    from qwen_tts import Qwen3TTSModel

    warnings.filterwarnings("ignore", message=".*flash-attn.*")
    _ensure_ffmpeg()

    use_cuda = torch.cuda.is_available()
    return Qwen3TTSModel.from_pretrained(
        "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
        device_map="cuda:0" if use_cuda else "cpu",
        dtype=torch.bfloat16 if use_cuda else torch.float32,
    )


async def get_tts_model():
    global MODEL
    if MODEL is not None:
        return MODEL

    async with MODEL_LOAD_LOCK:
        if MODEL is None:
            MODEL = await asyncio.to_thread(_load_tts_model_sync)
    return MODEL

@asynccontextmanager
async def lifespan(app: FastAPI):
    global MODEL
    database = create_configured_database()
    app.state.database = database
    try:
        yield
    finally:
        if database is not None:
            await database.close()
        MODEL = None

app = FastAPI(lifespan=lifespan)
app.include_router(auth_router)
app.include_router(studies_router)
app.include_router(feedback_router)

@app.get("/")
def read_root():
    return {"Heartbeat": "Hello World"}


@app.get("/health/database")
async def database_health(request: Request):
    database = getattr(request.app.state, "database", None)
    if database is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Database is not configured",
        )

    try:
        await database.ping()
    except (SQLAlchemyError, OSError, TimeoutError) as exc:
        logger.warning("Database readiness check failed", exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Database is unavailable",
        ) from exc

    return {"database": "ready"}

@app.post("/route/generate-path", response_model=WalkRouteResponse)
async def generate_route(
    payload: WalkRouteRequest,
    _auth: None = Depends(require_app_token),
):
    try:
        async with ROUTE_REQUEST_SEMAPHORE:
            result: WalkRouteResponse = await generate_walk_route(
                payload.source,
                payload.destination,
                payload.total_travel_time
            )
        return result 
    except Exception as e:
        logger.exception("Route generation failed")
        raise HTTPException(status_code=502, detail="Route generation failed") from e


@app.post("/route/generate-script/park", response_model=ScriptWithParkResponse)
async def generate_script_w_park(
    payload: ScriptWithParkRequest,
    _auth: None = Depends(require_app_token),
):
    try:
        async with SCRIPT_REQUEST_SEMAPHORE:
            result: str = await generate_script_with_park(
                payload.source,
                payload.destination,
                payload.park,
                payload.to_park_time,
                payload.park_time,
                payload.park_to_destination_time,
                payload.context,
                payload.study_parameters,
            )

        return {
            "script": result
        }

    except Exception as e:
        logger.exception("Park script generation failed")
        raise HTTPException(status_code=502, detail="Script generation failed") from e
    
@app.post("/route/generate-script/without-park", response_model=ScriptWithoutParkResponse)
async def generate_script_wo_park(
    payload: ScriptWithoutParkRequest,
    _auth: None = Depends(require_app_token),
):
    try:
        async with SCRIPT_REQUEST_SEMAPHORE:
            result: str = await generate_script_without_park(
                payload.source,
                payload.destination,
                payload.total_walking_time,
                payload.context,
                payload.study_parameters,
            )

        return {
            "script": result
        }

    except Exception as e:
        logger.exception("Baseline script generation failed")
        raise HTTPException(status_code=502, detail="Script generation failed") from e


@app.post("/tts")#, response_model=Response)
async def text_to_speech_endpoint(
    payload: TTSRequest,
    _auth: None = Depends(require_app_token),
):

    try:
        from route.services.text_to_speech import generate_tts_mp3

        model = await get_tts_model()
        result = await generate_tts_mp3(
            model=model,
            semaphore=TTS_SEMAPHORE,
            text=payload.text,
            language=payload.language,
            speaker=payload.speaker,
            instruct=payload.instruct,
            bitrate=payload.bitrate,
            offload_model_to_thread=True,
        )
    except Exception as e:
        logger.exception("TTS generation failed")
        raise HTTPException(status_code=500, detail="TTS generation failed") from e

    return result



if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="127.0.0.1",
        port=8443,
        reload=True,
        ssl_certfile="certs/cert.pem",
        ssl_keyfile="certs/key.pem",
    )
