import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

from fastapi import HTTPException

from route.auth import confirm_password_reset, request_password_reset
from route.models import PasswordResetConfirmRequest, PasswordResetRequest
from database.models import PasswordResetToken


class RequestPasswordResetTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalar = AsyncMock()
        self.session.execute = AsyncMock()
        self.session.commit = AsyncMock()

    @patch("route.auth.send_password_reset_email", new_callable=AsyncMock)
    async def test_known_email_creates_token_and_sends_email(self, send_mock):
        user_id = uuid4()
        self.session.scalar.return_value = user_id
        payload = PasswordResetRequest(email="Alice@Example.com")

        response = await request_password_reset(payload, self.session)

        self.assertIn("reset link has been sent", response.message)
        inserted_token = self.session.add.call_args.args[0]
        self.assertEqual(user_id, inserted_token.user_id)
        self.session.commit.assert_awaited_once()
        send_mock.assert_awaited_once()

    @patch("route.auth.send_password_reset_email", new_callable=AsyncMock)
    async def test_unknown_email_returns_generic_message_without_leaking(self, send_mock):
        self.session.scalar.return_value = None
        payload = PasswordResetRequest(email="nobody@example.com")

        response = await request_password_reset(payload, self.session)

        self.assertIn("reset link has been sent", response.message)
        self.session.add.assert_not_called()
        send_mock.assert_not_awaited()


class ConfirmPasswordResetTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalar = AsyncMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()

    @patch("route.auth.hash_password", new_callable=AsyncMock)
    async def test_valid_token_resets_password(self, hash_password_mock):
        hash_password_mock.return_value = "$argon2id$new-hash"
        token = PasswordResetToken(
            user_id=uuid4(),
            token_hash="irrelevant",
            expires_at=datetime.now(timezone.utc) + timedelta(minutes=10),
            used_at=None,
        )
        user = MagicMock(password_hash="$argon2id$old-hash")
        self.session.scalar.return_value = token
        self.session.get.return_value = user
        payload = PasswordResetConfirmRequest(token="raw-token", new_password="new password123")

        response = await confirm_password_reset(payload, self.session)

        self.assertEqual("Password has been reset", response.message)
        self.assertEqual("$argon2id$new-hash", user.password_hash)
        self.assertIsNotNone(token.used_at)
        self.session.commit.assert_awaited_once()

    async def test_expired_token_is_rejected(self):
        token = PasswordResetToken(
            user_id=uuid4(),
            token_hash="irrelevant",
            expires_at=datetime.now(timezone.utc) - timedelta(minutes=1),
            used_at=None,
        )
        self.session.scalar.return_value = token
        payload = PasswordResetConfirmRequest(token="raw-token", new_password="new password123")

        with self.assertRaises(HTTPException) as raised:
            await confirm_password_reset(payload, self.session)

        self.assertEqual(400, raised.exception.status_code)

    async def test_missing_token_is_rejected(self):
        self.session.scalar.return_value = None
        payload = PasswordResetConfirmRequest(token="bogus", new_password="new password123")

        with self.assertRaises(HTTPException) as raised:
            await confirm_password_reset(payload, self.session)

        self.assertEqual(400, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
