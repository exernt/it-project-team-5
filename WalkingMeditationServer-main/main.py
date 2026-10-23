import os
import asyncio
import logging
import secrets
from dataclasses import asdict
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from sqlalchemy.exc import SQLAlchemyError
from typing import Optional

from contextlib import asynccontextmanager

from route.auth import router as auth_router
from route.feedback import router as feedback_router
from route.questionnaires import router as questionnaires_router
from route.studies import router as studies_router
from route.study_questionnaires import router as study_questionnaires_router
from route.services.generate_script import GeneratedScript, generate_script_with_park, generate_script_without_park
from route.services.get_route import generate_walk_route
from route.errors import OpenaiAPIError
from route.models import WalkRouteRequest, WalkRouteResponse, ScriptWithParkRequest, ScriptWithParkResponse
from route.models import ScriptWithoutParkRequest, ScriptWithoutParkResponse, TTSRequest
from database.core import create_configured_database
from route.services.text_to_speech import generate_tts_mp3, PollyRequestError, PollyUnavailableError

logger = logging.getLogger(__name__)

TTS_SEMAPHORE = asyncio.Semaphore(1)
ROUTE_REQUEST_SEMAPHORE = asyncio.Semaphore(4)
SCRIPT_REQUEST_SEMAPHORE = asyncio.Semaphore(2)


def require_app_token(x_app_token: Optional[str] = Header(default=None)) -> None:
    expected = os.getenv("APP_API_TOKEN", "").strip()
    if not expected:
        return
    if x_app_token is None or not secrets.compare_digest(x_app_token, expected):
        raise HTTPException(status_code=401, detail="Invalid application token")


@asynccontextmanager
async def lifespan(app: FastAPI):
    database = create_configured_database()
    app.state.database = database
    try:
        yield
    finally:
        if database is not None:
            await database.close()

app = FastAPI(lifespan=lifespan)
app.include_router(auth_router)
app.include_router(studies_router)
app.include_router(questionnaires_router)
app.include_router(study_questionnaires_router)
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
            result: GeneratedScript = await generate_script_with_park(
                payload.source,
                payload.destination,
                payload.park,
                payload.to_park_time,
                payload.park_time,
                payload.park_to_destination_time,
                payload.context,
                payload.study_parameters,
                payload.source_to_park_steps,
                payload.park_to_destination_steps,
            )

        return {
            "script": result.script,
            "generation_cost_usd": result.usage.cost_usd,
            "usage": asdict(result.usage),
        }

    except OpenaiAPIError as e:
        logger.exception("Park script generation failed: OpenAI unavailable")
        raise HTTPException(status_code=503, detail="AI service unavailable") from e
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
            result: GeneratedScript = await generate_script_without_park(
                payload.source,
                payload.destination,
                payload.total_walking_time,
                payload.context,
                payload.study_parameters,
                payload.route_steps,
            )

        return {
            "script": result.script,
            "generation_cost_usd": result.usage.cost_usd,
            "usage": asdict(result.usage),
        }

    except OpenaiAPIError as e:
        logger.exception("Baseline script generation failed: OpenAI unavailable")
        raise HTTPException(status_code=503, detail="AI service unavailable") from e
    except Exception as e:
        logger.exception("Baseline script generation failed")
        raise HTTPException(status_code=502, detail="Script generation failed") from e


@app.post("/tts")
async def text_to_speech_endpoint(
        payload: TTSRequest,
        _auth: None = Depends(require_app_token),
):
    try:
        return await generate_tts_mp3(payload, TTS_SEMAPHORE)
    except PollyRequestError as e:
        raise HTTPException(status_code=422, detail="Polly rejected the speech settings or text") from e
    except PollyUnavailableError as e:
        logger.exception("Polly is unavailable")
        raise HTTPException(status_code=503, detail="Speech service unavailable") from e
    except Exception as e:
        logger.exception("TTS generation failed")
        raise HTTPException(status_code=502, detail="TTS generation failed") from e


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host="127.0.0.1",
        port=8000,
        reload=True,
        reload_excludes=[os.path.join("env", "*")],
    )
