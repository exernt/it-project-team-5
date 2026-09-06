import unittest
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

from fastapi import HTTPException

from route.auth import register_researcher, verify_researcher
from route.dependencies import require_role
from route.models import RegisterResearcherRequest
from route.persistence.orm_models import ResearcherProfile, Role, User


class RegisterResearcherTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()

    async def test_creates_profile_and_upgrades_role(self):
        user = User(id=uuid4(), email="alice@example.com", password_hash="hash", role=Role.PARTICIPANT)
        self.session.get.return_value = None  # no existing profile
        payload = RegisterResearcherRequest(institution="University of Example")

        response = await register_researcher(payload, user, self.session)

        self.assertEqual(Role.RESEARCHER, user.role)
        self.assertEqual("University of Example", response.institution)
        self.assertIsNone(response.verified_at)
        self.assertEqual("researcher", response.role)
        self.session.commit.assert_awaited_once()

    async def test_rejects_duplicate_profile(self):
        user = User(id=uuid4(), email="alice@example.com", password_hash="hash", role=Role.RESEARCHER)
        self.session.get.return_value = ResearcherProfile(user_id=user.id, institution="Existing Uni")
        payload = RegisterResearcherRequest(institution="New Uni")

        with self.assertRaises(HTTPException) as raised:
            await register_researcher(payload, user, self.session)

        self.assertEqual(409, raised.exception.status_code)


class VerifyResearcherTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()

    async def test_verifies_existing_profile(self):
        user_id = uuid4()
        profile = ResearcherProfile(user_id=user_id, institution="Example Uni", verified_at=None)
        user = User(id=user_id, email="alice@example.com", password_hash="hash", role=Role.RESEARCHER)
        self.session.get.side_effect = [profile, user]

        response = await verify_researcher(user_id, self.session)

        self.assertIsNotNone(profile.verified_at)
        self.assertEqual("Example Uni", response.institution)
        self.session.commit.assert_awaited_once()

    async def test_missing_profile_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await verify_researcher(uuid4(), self.session)

        self.assertEqual(404, raised.exception.status_code)


class RequireRoleTests(unittest.IsolatedAsyncioTestCase):
    async def test_allows_matching_role(self):
        user = User(id=uuid4(), email="admin@example.com", password_hash="hash", role=Role.ADMIN)
        check = require_role(Role.ADMIN)

        result = await check(user)

        self.assertEqual(user, result)

    async def test_rejects_non_matching_role(self):
        user = User(id=uuid4(), email="alice@example.com", password_hash="hash", role=Role.PARTICIPANT)
        check = require_role(Role.ADMIN)

        with self.assertRaises(HTTPException) as raised:
            await check(user)

        self.assertEqual(403, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()