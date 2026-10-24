import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

import jwt
from fastapi import HTTPException

from route.auth import get_current_user_info, login, logout
from route.dependencies import get_current_user
from route.models import LoginRequest, LogoutRequest
from database.models import RefreshToken, ResearcherProfile, Role, User
from route.services.tokens import create_access_token


class LoginTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalar = AsyncMock()
        self.session.commit = AsyncMock()

    @patch("route.auth.verify_password", new_callable=AsyncMock)
    async def test_correct_credentials_return_tokens(self, verify_mock):
        verify_mock.return_value = True
        user = User(
            id=uuid4(),
            email="bob@example.com",
            password_hash="$argon2id$existing-hash",
            session_version=2,
        )
        self.session.scalar.return_value = user
        payload = LoginRequest(email="Bob@Example.com", password="correcthorsebatterystaple")

        response = await login(payload, self.session)

        self.assertTrue(response.access_token)
        self.assertTrue(response.refresh_token)
        self.assertEqual("bearer", response.token_type)
        inserted_token = self.session.add.call_args.args[0]
        self.assertEqual(user.id, inserted_token.user_id)
        self.assertIsNotNone(inserted_token.family_id)
        self.assertIsNone(inserted_token.parent_id)
        self.session.commit.assert_awaited_once()

    @patch("route.auth.verify_password", new_callable=AsyncMock)
    async def test_unknown_email_rejected(self, verify_mock):
        self.session.scalar.return_value = None
        payload = LoginRequest(email="nobody@example.com", password="whatever123")

        with self.assertRaises(HTTPException) as raised:
            await login(payload, self.session)

        self.assertEqual(401, raised.exception.status_code)
        verify_mock.assert_not_awaited()
        self.session.add.assert_not_called()

    @patch("route.auth.verify_password", new_callable=AsyncMock)
    async def test_wrong_password_rejected(self, verify_mock):
        verify_mock.return_value = False
        user = User(id=uuid4(), email="bob@example.com", password_hash="$argon2id$existing-hash")
        self.session.scalar.return_value = user
        payload = LoginRequest(email="bob@example.com", password="wrongpassword")

        with self.assertRaises(HTTPException) as raised:
            await login(payload, self.session)

        self.assertEqual(401, raised.exception.status_code)
        self.session.add.assert_not_called()


class LogoutTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.execute = AsyncMock()
        self.session.scalar = AsyncMock()
        self.session.scalar.return_value = None
        self.session.commit = AsyncMock()

    async def test_logout_revokes_token_and_returns_generic_message(self):
        payload = LogoutRequest(refresh_token="some-refresh-token")
        self.session.scalar.return_value = RefreshToken(
            user_id=uuid4(),
            family_id=uuid4(),
            token_hash="hash",
            expires_at=datetime.now(timezone.utc) + timedelta(days=1),
        )

        response = await logout(payload, self.session)

        self.assertEqual("Logged out", response.message)
        self.session.execute.assert_awaited_once()
        self.session.commit.assert_awaited_once()

    async def test_logout_with_unknown_token_still_succeeds(self):
        # Logout is idempotent: an unknown/already-revoked token isn't an error.
        payload = LogoutRequest(refresh_token="garbage-token")

        response = await logout(payload, self.session)

        self.assertEqual("Logged out", response.message)


class GetCurrentUserTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()

    async def test_valid_token_returns_user(self):
        user_id = uuid4()
        user = User(
            id=user_id,
            email="bob@example.com",
            password_hash="hash",
            session_version=0,
        )
        self.session.get.return_value = user
        token = create_access_token(user_id)
        credentials = MagicMock(credentials=token)

        result = await get_current_user(credentials, self.session)

        self.assertEqual(user, result)

    async def test_invalid_token_rejected(self):
        credentials = MagicMock(credentials="not-a-real-jwt")

        with self.assertRaises(HTTPException) as raised:
            await get_current_user(credentials, self.session)

        self.assertEqual(401, raised.exception.status_code)

    async def test_old_session_version_is_rejected(self):
        user_id = uuid4()
        self.session.get.return_value = User(
            id=user_id,
            email="bob@example.com",
            password_hash="hash",
            session_version=3,
        )
        credentials = MagicMock(credentials=create_access_token(user_id, session_version=2))

        with self.assertRaises(HTTPException) as raised:
            await get_current_user(credentials, self.session)

        self.assertEqual(401, raised.exception.status_code)

    async def test_expired_token_rejected(self):
        from route.config import jwt_algorithm, jwt_secret_key

        expired_payload = {
            "sub": str(uuid4()),
            "iat": datetime.now(timezone.utc) - timedelta(hours=1),
            "exp": datetime.now(timezone.utc) - timedelta(minutes=1),
        }
        expired_token = jwt.encode(expired_payload, jwt_secret_key(), algorithm=jwt_algorithm())
        credentials = MagicMock(credentials=expired_token)

        with self.assertRaises(HTTPException) as raised:
            await get_current_user(credentials, self.session)

        self.assertEqual(401, raised.exception.status_code)

class GetCurrentUserInfoTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()

    async def test_participant_has_no_researcher_fields(self):
        user = User(
            id=uuid4(),
            email="bob@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

        response = await get_current_user_info(user, self.session)

        self.assertEqual("participant", response.role)
        self.assertIsNone(response.institution)
        self.assertIsNone(response.verified_at)
        self.session.get.assert_not_awaited()

    async def test_verified_researcher_includes_profile(self):
        user = User(
            id=uuid4(),
            email="alice@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        profile = ResearcherProfile(
            user_id=user.id,
            institution="University of Melbourne",
            verified_at=datetime.now(timezone.utc),
        )
        self.session.get.return_value = profile

        response = await get_current_user_info(user, self.session)

        self.assertEqual("researcher", response.role)
        self.assertEqual("University of Melbourne", response.institution)
        self.assertIsNotNone(response.verified_at)

    async def test_unverified_researcher_has_null_verified_at(self):
        user = User(
            id=uuid4(),
            email="dave@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        profile = ResearcherProfile(
            user_id=user.id,
            institution="Somewhere",
            verified_at=None,
        )
        self.session.get.return_value = profile

        response = await get_current_user_info(user, self.session)

        self.assertIsNone(response.verified_at)


if __name__ == "__main__":
    unittest.main()
