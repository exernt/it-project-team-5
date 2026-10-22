from __future__ import annotations

import asyncio
import html
import logging
from dataclasses import dataclass
from email.message import EmailMessage
from urllib.parse import quote, urlsplit, urlunsplit
from uuid import uuid4

from route.config import (
    aws_region,
    email_backend,
    email_capture_directory,
    password_reset_from_email,
    password_reset_link_base,
    password_reset_reply_to_email,
    ses_configuration_set,
)

logger = logging.getLogger(__name__)


class EmailDeliveryError(RuntimeError):
    """A transactional email could not be handed to its configured backend."""


@dataclass(frozen=True)
class EmailDeliveryResult:
    message_id: str


def build_password_reset_link(token: str) -> str:
    """Put the credential in the URL fragment so HTTP servers never receive it."""
    base = urlsplit(password_reset_link_base())
    fragment = f"token={quote(token, safe='')}"
    return urlunsplit((base.scheme, base.netloc, base.path, base.query, fragment))


def _message_bodies(link: str) -> tuple[str, str]:
    text_body = (
        "A password reset was requested for your Walking Meditation account.\n\n"
        f"Open this link to choose a new password:\n{link}\n\n"
        "This link expires in 30 minutes and can only be used once. "
        "If you did not request a reset, you can ignore this email."
    )
    safe_link = html.escape(link, quote=True)
    html_body = f"""<!doctype html>
<html><body>
<p>A password reset was requested for your Walking Meditation account.</p>
<p><a href="{safe_link}">Choose a new password</a></p>
<p>This link expires in 30 minutes and can only be used once.</p>
<p>If you did not request a reset, you can ignore this email.</p>
</body></html>"""
    return text_body, html_body


def _write_capture_email(
        recipient: str,
        text_body: str,
        html_body: str,
) -> str:
    capture_directory = email_capture_directory()
    capture_directory.mkdir(parents=True, exist_ok=True)
    message_id = uuid4().hex
    message = EmailMessage()
    message["From"] = password_reset_from_email()
    message["To"] = recipient
    message["Subject"] = "Reset your Walking Meditation password"
    message["Message-ID"] = f"<{message_id}@local.walkingmeditation>"
    message.set_content(text_body)
    message.add_alternative(html_body, subtype="html")
    path = capture_directory / f"password-reset-{message_id}.eml"
    path.write_bytes(message.as_bytes())
    return message_id


def _send_ses_email_sync(
        recipient: str,
        text_body: str,
        html_body: str,
) -> str:
    # Imported lazily so local/test use does not require AWS credentials or SDK startup.
    import boto3

    client = boto3.client("ses", region_name=aws_region())
    request = {
        "Source": password_reset_from_email(),
        "Destination": {"ToAddresses": [recipient]},
        "Message": {
            "Subject": {
                "Data": "Reset your Walking Meditation password",
                "Charset": "UTF-8",
            },
            "Body": {
                "Text": {"Data": text_body, "Charset": "UTF-8"},
                "Html": {"Data": html_body, "Charset": "UTF-8"},
            },
        },
    }
    reply_to = password_reset_reply_to_email()
    if reply_to:
        request["ReplyToAddresses"] = [reply_to]
    configuration_set = ses_configuration_set()
    if configuration_set:
        request["ConfigurationSetName"] = configuration_set
    response = client.send_email(**request)
    return response["MessageId"]


async def send_password_reset_email(email: str, token: str) -> EmailDeliveryResult:
    """Deliver a reset link without writing the credential to application logs.

    The file backend captures an RFC 822 message under ``var/dev-mail`` for
    local testing. The SES backend uses AWS's default credential chain, which
    means deployed instances should receive permission through an IAM role.
    """
    try:
        link = build_password_reset_link(token)
        text_body, html_body = _message_bodies(link)
        backend = email_backend()
        if backend == "file":
            message_id = await asyncio.to_thread(
                _write_capture_email,
                email,
                text_body,
                html_body,
            )
        else:
            message_id = await asyncio.to_thread(
                _send_ses_email_sync,
                email,
                text_body,
                html_body,
            )
    except Exception as exc:
        raise EmailDeliveryError("Password reset email delivery failed") from exc

    logger.info(
        "Password reset email accepted by %s; message_id=%s",
        backend,
        message_id,
    )
    return EmailDeliveryResult(message_id=message_id)
