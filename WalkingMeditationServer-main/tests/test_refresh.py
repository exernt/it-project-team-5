import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException

from route.auth import refresh
from route.models import RefreshRequest
from database.models import RefreshToken, User


class RefreshTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalar = AsyncMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.session.commit = AsyncMock()

    async def test_valid_token_rotates_and_returns_new_tokens(self):
        user_id = uuid4()
        token_id = uuid4()
        family_id = uuid4()
        old_token = RefreshToken(
            id=token_id,
            user_id=user_id,
            family_id=family_id,
            token_hash="irrelevant",
            expires_at=datetime.now(timezone.utc) + timedelta(days=10),
            revoked_at=None,
        )
        self.session.scalar.return_value = old_token
        self.session.get.return_value = User(
            id=user_id,
            email="alice@example.com",
            password_hash="hash",
            session_version=4,
        )
        payload = RefreshRequest(refresh_token="some-raw-token")

        response = await refresh(payload, self.session)

        self.assertTrue(response.access_token)
        self.assertTrue(response.refresh_token)
        self.assertEqual("bearer", response.token_type)
        self.assertIsNotNone(old_token.revoked_at)

        inserted_token = self.session.add.call_args.args[0]
        self.assertEqual(user_id, inserted_token.user_id)
        self.assertEqual(family_id, inserted_token.family_id)
        self.assertEqual(token_id, inserted_token.parent_id)
        self.session.commit.assert_awaited_once()

    async def test_expired_token_rejected(self):
        expired_token = RefreshToken(
            user_id=uuid4(),
            token_hash="irrelevant",
            expires_at=datetime.now(timezone.utc) - timedelta(minutes=1),
            revoked_at=None,
        )
        self.session.scalar.return_value = expired_token
        payload = RefreshRequest(refresh_token="some-raw-token")

        with self.assertRaises(HTTPException) as raised:
            await refresh(payload, self.session)

        self.assertEqual(401, raised.exception.status_code)
        self.session.add.assert_not_called()

    async def test_already_revoked_token_rejected(self):
        revoked_token = RefreshToken(
            user_id=uuid4(),
            family_id=uuid4(),
            token_hash="irrelevant",
            expires_at=datetime.now(timezone.utc) + timedelta(days=10),
            revoked_at=datetime.now(timezone.utc) - timedelta(minutes=5),
        )
        self.session.scalar.return_value = revoked_token
        payload = RefreshRequest(refresh_token="some-raw-token")

        with self.assertRaises(HTTPException) as raised:
            await refresh(payload, self.session)

        self.assertEqual(401, raised.exception.status_code)
        self.session.execute.assert_awaited_once()
        self.session.commit.assert_awaited_once()
        self.session.add.assert_not_called()

    async def test_unknown_token_rejected(self):
        self.session.scalar.return_value = None
        payload = RefreshRequest(refresh_token="bogus-token")

        with self.assertRaises(HTTPException) as raised:
            await refresh(payload, self.session)

        self.assertEqual(401, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
