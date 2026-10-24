import asyncio
import logging
logging.basicConfig(level=logging.INFO)
from dataclasses import asdict
from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import HTMLResponse, JSONResponse
from sqlalchemy.exc import SQLAlchemyError
from typing import Optional

from contextlib import asynccontextmanager

from route.auth import router as auth_router
from route.dependencies import CurrentUserDependency
from route.feedback import router as feedback_router
from route.questionnaires import router as questionnaires_router
from route.studies import router as studies_router
from route.study_questionnaires import router as study_questionnaires_router
from route.services.generate_script import GeneratedScript, generate_script_with_park, generate_script_without_park
from route.services.get_route import generate_walk_route
from route.errors import OpenaiAPIError
from route.config import (
    android_app_package_name,
    android_app_sha256_cert_fingerprints,
)
from route.models import WalkRouteRequest, WalkRouteResponse, ScriptWithParkRequest, ScriptWithParkResponse
from route.models import ScriptWithoutParkRequest, ScriptWithoutParkResponse, TTSRequest
from database.core import create_configured_database
from route.services.text_to_speech import generate_tts_mp3, PollyRequestError, PollyUnavailableError


logger = logging.getLogger(__name__)

TTS_SEMAPHORE = asyncio.Semaphore(1)
ROUTE_REQUEST_SEMAPHORE = asyncio.Semaphore(4)
SCRIPT_REQUEST_SEMAPHORE = asyncio.Semaphore(2)


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

@app.get("/.well-known/assetlinks.json", response_model=None)
async def android_asset_links():
    fingerprints = android_app_sha256_cert_fingerprints()
    if not fingerprints:
        raise HTTPException(status_code=404, detail="App Link verification is not configured")
    return JSONResponse(
        content=[
            {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                    "namespace": "android_app",
                    "package_name": android_app_package_name(),
                    "sha256_cert_fingerprints": fingerprints,
                },
            }
        ]
    )


@app.get("/auth/password-reset/open", response_model=None)
async def password_reset_landing_page():
    # The reset credential is deliberately carried in the URL fragment. URL
    # fragments are available to this browser script but are not sent to the
    # web server, reverse proxy, or access log.
    html_content = """
    <!doctype html>
    <html>
    <head>
        <meta charset="utf-8">
        <meta name="referrer" content="no-referrer">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Reset Password - Walking Meditation</title>
    </head>
    <body style="font-family: sans-serif; text-align: center; padding: 50px 20px;">
        <h2>Open Walking Meditation</h2>
        <p>Continue in the app to choose a new password.</p>
        <p><a id="open-app" href="walkingmeditation://password-reset">Open the app</a></p>
        <script>
            const target = "walkingmeditation://password-reset" + window.location.hash;
            document.getElementById("open-app").href = target;
            window.location.replace(target);
        </script>
    </body>
    </html>
    """
    return HTMLResponse(
        content=html_content,
        headers={
            "Cache-Control": "no-store",
            "Referrer-Policy": "no-referrer",
            "X-Content-Type-Options": "nosniff",
        },
    )


@app.get("/study/join", response_model=None)
async def study_join_redirect(id: str):
    deep_link = f"walkingmeditation://study/join?id={id}"
    html_content = f"""
    <!DOCTYPE html>
    <html>
    <head>
        <title>Join Study - Walking Meditation</title>
        <meta http-equiv="refresh" content="0;url={deep_link}">
    </head>
    <body style="font-family: sans-serif; text-align: center; padding-top: 50px;">
        <h2>Opening Walking Meditation App...</h2>
        <p>If the app does not open automatically, <a href="{deep_link}">click here to join</a>.</p>
    </body>
    </html>
    """
    return HTMLResponse(content=html_content)

@app.post("/route/generate-path", response_model=WalkRouteResponse)
async def generate_route(
        payload: WalkRouteRequest,
        _user: CurrentUserDependency,
):
    try:
        async with ROUTE_REQUEST_SEMAPHORE:
            result: WalkRouteResponse = await generate_walk_route(
                payload.source,
                payload.destination,
                payload.total_travel_time
            )
        return result
        return result
    except Exception as e:
        logger.exception("Route generation failed")
        raise HTTPException(status_code=502, detail="Route generation failed") from e


@app.post("/route/generate-script/park", response_model=ScriptWithParkResponse)
async def generate_script_w_park(
        payload: ScriptWithParkRequest,
        _user: CurrentUserDependency,
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
            "script": result
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
        _user: CurrentUserDependency,
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
        _user: CurrentUserDependency,
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
        port=8443,
        reload=True,
        ssl_certfile="certs/cert.pem",
        ssl_keyfile="certs/key.pem",
    )
