# routes/config.py
from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import urlsplit
from dotenv import load_dotenv


BASE_DIR = Path(__file__).resolve().parent
SERVER_DIR = BASE_DIR.parent
PROMPT_FOLDER = BASE_DIR / "prompts"

# Load environment variables once for the package.
load_dotenv()

GOOGLE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
GOOGLE_PLACES_NEARBY_URL = "https://places.googleapis.com/v1/places:searchNearby"


def database_url() -> str | None:
    """Return the configured SQLAlchemy database URL, if one is configured."""
    value = os.getenv("DATABASE_URL", "").strip()
    return value or None


def _positive_int_env(name: str, default: int) -> int:
    raw_value = os.getenv(name, str(default)).strip()
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc

    if value < 1:
        raise ValueError(f"{name} must be greater than zero")
    return value


def database_pool_size() -> int:
    return _positive_int_env("DATABASE_POOL_SIZE", 5)


def database_max_overflow() -> int:
    raw_value = os.getenv("DATABASE_MAX_OVERFLOW", "5").strip()
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError("DATABASE_MAX_OVERFLOW must be an integer") from exc

    if value < 0:
        raise ValueError("DATABASE_MAX_OVERFLOW cannot be negative")
    return value


def database_connect_timeout_seconds() -> int:
    return _positive_int_env("DATABASE_CONNECT_TIMEOUT_SECONDS", 5)

def google_maps_api_key() -> str:
    key = os.getenv("GOOGLE_MAPS_API_KEY")
    if not key:
        # Import here to avoid circular imports.
        from .errors import GoogleRoutesError

        raise GoogleRoutesError("Missing env var GOOGLE_MAPS_API_KEY")
    return key


def park_fallback_enabled() -> bool:
    """Whether route search may supplement city parks and gardens with generic parks."""
    value = os.getenv("ENABLE_PARK_FALLBACK", "false").strip().casefold()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off", ""}:
        return False
    raise ValueError(
        "ENABLE_PARK_FALLBACK must be one of true/false, 1/0, yes/no, or on/off"
    )


def openai_api_key()->str:
    key = os.getenv("OPENAI_API_KEY")
    if not key:
        from .errors import OpenaiAPIError
        raise OpenaiAPIError("Missing env var OPENAI_API_KEY")
    return key


def openai_timeout_seconds() -> float:
    """Per-request timeout for OpenAI calls (env OPENAI_TIMEOUT_SECONDS)."""
    raw_value = os.getenv("OPENAI_TIMEOUT_SECONDS", "60").strip()
    try:
        value = float(raw_value)
    except ValueError as exc:
        raise ValueError("OPENAI_TIMEOUT_SECONDS must be a number") from exc
    if value <= 0:
        raise ValueError("OPENAI_TIMEOUT_SECONDS must be greater than zero")
    return value


def openai_max_retries() -> int:
    """SDK-level retries for transient OpenAI failures (env OPENAI_MAX_RETRIES)."""
    raw_value = os.getenv("OPENAI_MAX_RETRIES", "2").strip()
    try:
        value = int(raw_value)
    except ValueError as exc:
        raise ValueError("OPENAI_MAX_RETRIES must be an integer") from exc
    if value < 0:
        raise ValueError("OPENAI_MAX_RETRIES cannot be negative")
    return value


def nearby_context_enabled() -> bool:
    """Whether script generation enriches route segments with nearby landmarks."""
    value = os.getenv("ENABLE_NEARBY_CONTEXT", "false").strip().casefold()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off", ""}:
        return False
    raise ValueError(
        "ENABLE_NEARBY_CONTEXT must be one of true/false, 1/0, yes/no, or on/off"
    )

def script_fallback_enabled() -> bool:
    """Whether a generic script replaces failed OpenAI generation (default: yes).

    Set SCRIPT_FALLBACK_ENABLED=false to return an error instead, e.g. when
    canned scripts must never reach study participants.
    """
    value = os.getenv("SCRIPT_FALLBACK_ENABLED", "true").strip().casefold()
    if value in {"1", "true", "yes", "on", ""}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    raise ValueError(
        "SCRIPT_FALLBACK_ENABLED must be one of true/false, 1/0, yes/no, or on/off"
    )

# Bump when any file in prompts/ changes in a way that affects generated output,
# so stored scripts can be traced back to the prompt that produced them.
PROMPT_VERSION = "narration-2026-10-v3"


def load_template(path: str) -> str:
    try:
        return Path(path).read_text(encoding="utf-8")
    except Exception as e:
        raise RuntimeError(f"Unable to load prompt template: {path}") from e

def render_template(template: str, **kwargs) -> str:
    try:
        return template.format(**kwargs)
    except KeyError as e:
        raise ValueError(f"Missing template variable: {e}")

def get_script_generation_prompt(meditation_style:str ) -> str:
    if meditation_style == "WITHOUT_PARK":
        return load_template(PROMPT_FOLDER / "without_park_narration.txt")
    else:
        return load_template(PROMPT_FOLDER / "with_park_narration.txt")

def jwt_secret_key() -> str:
    key = os.getenv("JWT_SECRET_KEY", "").strip()
    if not key:
        raise ValueError("Missing env var JWT_SECRET_KEY")
    return key


def jwt_algorithm() -> str:
    return os.getenv("JWT_ALGORITHM", "HS256").strip()


def access_token_ttl_minutes() -> int:
    return _positive_int_env("ACCESS_TOKEN_TTL_MINUTES", 15)


def refresh_token_ttl_days() -> int:
    return _positive_int_env("REFRESH_TOKEN_TTL_DAYS", 30)


def email_backend() -> str:
    """Return the transactional-email backend used by the server."""
    backend = os.getenv("EMAIL_BACKEND", "file").strip().casefold()
    if backend not in {"file", "ses"}:
        raise ValueError("EMAIL_BACKEND must be either 'file' or 'ses'")
    return backend


def email_capture_directory() -> Path:
    raw_value = os.getenv("EMAIL_CAPTURE_DIRECTORY", "var/dev-mail").strip()
    if not raw_value:
        raise ValueError("EMAIL_CAPTURE_DIRECTORY cannot be empty")
    path = Path(raw_value)
    return path if path.is_absolute() else SERVER_DIR / path


def password_reset_from_email() -> str:
    value = os.getenv(
        "PASSWORD_RESET_FROM_EMAIL",
        "Walking Meditation <no-reply@localhost>",
    ).strip()
    if not value:
        raise ValueError("PASSWORD_RESET_FROM_EMAIL cannot be empty")
    return value


def password_reset_reply_to_email() -> str | None:
    value = os.getenv("PASSWORD_RESET_REPLY_TO_EMAIL", "").strip()
    return value or None


def password_reset_link_base() -> str:
    value = os.getenv(
        "PASSWORD_RESET_LINK_BASE",
        "walkingmeditation://password-reset",
    ).strip()
    parsed = urlsplit(value)
    if not parsed.scheme:
        raise ValueError("PASSWORD_RESET_LINK_BASE must be an absolute URL")
    is_local_app_link = (
        parsed.scheme.casefold() == "walkingmeditation"
        and parsed.netloc.casefold() == "password-reset"
    )
    is_https_app_link = (
        parsed.scheme.casefold() == "https"
        and bool(parsed.netloc)
        and parsed.path == "/auth/password-reset/open"
    )
    if not is_local_app_link and not is_https_app_link:
        raise ValueError(
            "PASSWORD_RESET_LINK_BASE must be the local app URI or an HTTPS "
            "URL ending in /auth/password-reset/open"
        )
    if parsed.query:
        raise ValueError("PASSWORD_RESET_LINK_BASE must not contain a query string")
    if parsed.fragment:
        raise ValueError("PASSWORD_RESET_LINK_BASE must not contain a fragment")
    return value


def password_reset_request_cooldown_seconds() -> int:
    return _positive_int_env("PASSWORD_RESET_REQUEST_COOLDOWN_SECONDS", 60)


def aws_region() -> str:
    value = (
        os.getenv("AWS_REGION", "").strip()
        or os.getenv("AWS_DEFAULT_REGION", "").strip()
    )
    if not value:
        raise ValueError("AWS_REGION must be configured when EMAIL_BACKEND=ses")
    return value


def ses_configuration_set() -> str | None:
    value = os.getenv("SES_CONFIGURATION_SET", "").strip()
    return value or None


def android_app_package_name() -> str:
    value = os.getenv(
        "ANDROID_APP_PACKAGE_NAME",
        "com.example.walking_meditation",
    ).strip()
    if not value:
        raise ValueError("ANDROID_APP_PACKAGE_NAME cannot be empty")
    return value


def android_app_sha256_cert_fingerprints() -> list[str]:
    value = os.getenv("ANDROID_APP_SHA256_CERT_FINGERPRINTS", "")
    return [item.strip() for item in value.split(",") if item.strip()]
