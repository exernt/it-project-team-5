from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


async def send_password_reset_email(email: str, token: str) -> None:
    """Send the password reset token to the user.

    For local development this just logs the token. Swap this out for a
    real provider (SES, SMTP, etc.) before deploying.
    """
    logger.info("Password reset token for %s: %s", email, token)