import tempfile
import unittest
import sys
from email import policy
from email.parser import BytesParser
from pathlib import Path
from unittest.mock import MagicMock, patch

from route.services.email_sender import (
    EmailDeliveryError,
    _send_ses_email_sync,
    build_password_reset_link,
    send_password_reset_email,
)


class PasswordResetLinkTests(unittest.TestCase):
    def test_custom_scheme_link_keeps_token_in_fragment(self):
        with patch.dict(
            "os.environ",
            {"PASSWORD_RESET_LINK_BASE": "walkingmeditation://password-reset"},
        ):
            link = build_password_reset_link("safe_token-123")

        self.assertEqual(
            "walkingmeditation://password-reset#token=safe_token-123",
            link,
        )
        self.assertNotIn("?token=", link)

    def test_https_link_keeps_token_out_of_request_target(self):
        with patch.dict(
            "os.environ",
            {
                "PASSWORD_RESET_LINK_BASE": (
                    "https://api.example.com/auth/password-reset/open"
                )
            },
        ):
            link = build_password_reset_link("safe_token-123")

        request_target, fragment = link.split("#", 1)
        self.assertEqual(
            "https://api.example.com/auth/password-reset/open",
            request_target,
        )
        self.assertEqual("token=safe_token-123", fragment)

    def test_http_reset_link_is_rejected(self):
        with patch.dict(
            "os.environ",
            {
                "PASSWORD_RESET_LINK_BASE": (
                    "http://api.example.com/auth/password-reset/open"
                )
            },
        ):
            with self.assertRaises(ValueError):
                build_password_reset_link("safe_token-123")


class SesRequestTests(unittest.TestCase):
    def test_ses_uses_region_and_default_credential_chain(self):
        client = MagicMock()
        client.send_email.return_value = {"MessageId": "message-123"}
        boto3_module = MagicMock()
        boto3_module.client.return_value = client

        with patch.dict(sys.modules, {"boto3": boto3_module}):
            with patch.dict(
                "os.environ",
                {
                    "AWS_REGION": "ap-southeast-2",
                    "PASSWORD_RESET_FROM_EMAIL": "Walking Meditation <no-reply@example.com>",
                    "PASSWORD_RESET_REPLY_TO_EMAIL": "support@example.com",
                    "SES_CONFIGURATION_SET": "transactional",
                },
            ):
                message_id = _send_ses_email_sync(
                    "alice@example.com",
                    "plain body",
                    "<p>HTML body</p>",
                )

        self.assertEqual("message-123", message_id)
        boto3_module.client.assert_called_once_with(
            "ses",
            region_name="ap-southeast-2",
        )
        request = client.send_email.call_args.kwargs
        self.assertEqual(
            "Walking Meditation <no-reply@example.com>",
            request["Source"],
        )
        self.assertEqual(["alice@example.com"], request["Destination"]["ToAddresses"])
        self.assertEqual(["support@example.com"], request["ReplyToAddresses"])
        self.assertEqual("transactional", request["ConfigurationSetName"])


class EmailSenderTests(unittest.IsolatedAsyncioTestCase):
    async def test_file_backend_captures_email_without_logging_token(self):
        token = "a_secret_reset_token_that_must_not_be_logged_123"
        with tempfile.TemporaryDirectory() as temporary_directory:
            with patch.dict(
                "os.environ",
                {
                    "EMAIL_BACKEND": "file",
                    "EMAIL_CAPTURE_DIRECTORY": temporary_directory,
                    "PASSWORD_RESET_LINK_BASE": "walkingmeditation://password-reset",
                },
            ):
                with self.assertLogs(
                    "route.services.email_sender",
                    level="INFO",
                ) as captured_logs:
                    result = await send_password_reset_email(
                        "alice@example.com",
                        token,
                    )

            files = list(Path(temporary_directory).glob("*.eml"))
            self.assertEqual(1, len(files))
            message = BytesParser(policy=policy.default).parsebytes(files[0].read_bytes())
            text_body = next(
                part.get_content()
                for part in message.walk()
                if part.get_content_type() == "text/plain"
            )
            self.assertIn(token, text_body)
            self.assertTrue(result.message_id)
            self.assertNotIn(token, "\n".join(captured_logs.output))
            self.assertNotIn("alice@example.com", "\n".join(captured_logs.output))

    @patch(
        "route.services.email_sender._send_ses_email_sync",
        return_value="ses-message-id",
    )
    async def test_ses_backend_returns_provider_message_id(self, send_mock):
        with patch.dict(
            "os.environ",
            {
                "EMAIL_BACKEND": "ses",
                "PASSWORD_RESET_LINK_BASE": "https://api.example.com/auth/password-reset/open",
            },
        ):
            result = await send_password_reset_email(
                "alice@example.com",
                "safe_token-123",
            )

        self.assertEqual("ses-message-id", result.message_id)
        send_mock.assert_called_once()

    async def test_backend_configuration_error_is_wrapped(self):
        with patch.dict("os.environ", {"EMAIL_BACKEND": "invalid"}):
            with self.assertRaises(EmailDeliveryError):
                await send_password_reset_email(
                    "alice@example.com",
                    "safe_token-123",
                )


if __name__ == "__main__":
    unittest.main()
