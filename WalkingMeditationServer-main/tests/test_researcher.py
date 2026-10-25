import unittest
from unittest.mock import AsyncMock, MagicMock
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import HTTPException

from route.auth import list_pending_researchers, register_researcher, verify_researcher
from route.dependencies import require_role
from route.models import RegisterResearcherRequest
from database.models import ResearcherProfile, Role, User


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

    async def test_creates_profile_without_institution(self):
        user = User(id=uuid4(), email="alice@example.com", password_hash="hash", role=Role.PARTICIPANT)
        self.session.get.return_value = None
        payload = RegisterResearcherRequest(institution=None)

        response = await register_researcher(payload, user, self.session)

        self.assertEqual(Role.RESEARCHER, user.role)
        self.assertIsNone(response.institution)


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

    async def test_reverifying_already_verified_profile_is_idempotent(self):
        user_id = uuid4()
        original_verified_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        profile = ResearcherProfile(user_id=user_id, institution="Example Uni", verified_at=original_verified_at)
        user = User(id=user_id, email="alice@example.com", password_hash="hash", role=Role.RESEARCHER)
        self.session.get.side_effect = [profile, user]

        response = await verify_researcher(user_id, self.session)

        self.assertIsNotNone(response.verified_at)
        self.assertGreaterEqual(response.verified_at, original_verified_at)


class ListPendingResearchersTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.execute = AsyncMock()

    async def test_returns_unverified_profiles_with_email(self):
        user_id = uuid4()
        profile = ResearcherProfile(
            user_id=user_id,
            institution="Example Uni",
            verified_at=None,
            created_on=datetime(2026, 1, 1, tzinfo=timezone.utc),
        )
        execute_result = MagicMock()
        execute_result.all.return_value = [(profile, "alice@example.com")]
        self.session.execute.return_value = execute_result

        response = await list_pending_researchers(self.session)

        self.assertEqual(1, len(response.researchers))
        self.assertEqual(user_id, response.researchers[0].user_id)
        self.assertEqual("alice@example.com", response.researchers[0].email)
        self.assertEqual("Example Uni", response.researchers[0].institution)

    async def test_returns_empty_list_when_none_pending(self):
        execute_result = MagicMock()
        execute_result.all.return_value = []
        self.session.execute.return_value = execute_result

        response = await list_pending_researchers(self.session)

        self.assertEqual([], response.researchers)


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