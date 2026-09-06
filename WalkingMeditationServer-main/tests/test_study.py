import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from pydantic import ValidationError

from route.dependencies import get_verified_researcher
from route.models import CreateStudyRequest
from route.persistence.orm_models import ResearcherProfile, Role, Study, User
from route.studies import create_study


class CreateStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.commit = AsyncMock()
        self.researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )

    async def test_creates_study(self):
        payload = CreateStudyRequest(name="  Morning walks  ", duration=14)

        response = await create_study(payload, self.researcher, self.session)

        added_study = self.session.add.call_args.args[0]
        self.assertIsInstance(added_study, Study)
        self.assertEqual("Morning walks", added_study.name)
        self.assertEqual(14, added_study.duration)
        self.assertEqual(added_study.id, response.id)
        self.assertEqual("Morning walks", response.name)
        self.assertEqual(14, response.duration)
        self.session.commit.assert_awaited_once()

    def test_rejects_non_positive_duration(self):
        with self.assertRaises(ValidationError):
            CreateStudyRequest(name="Morning walks", duration=0)

    def test_rejects_blank_name(self):
        with self.assertRaises(ValidationError):
            CreateStudyRequest(name="   ", duration=14)


class VerifiedResearcherTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()

    async def test_allows_verified_researcher(self):
        researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.session.get.return_value = ResearcherProfile(
            user_id=researcher.id,
            verified_at=datetime.now(timezone.utc),
        )

        result = await get_verified_researcher(researcher, self.session)

        self.assertIs(researcher, result)

    async def test_rejects_unverified_researcher(self):
        researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.session.get.return_value = ResearcherProfile(
            user_id=researcher.id,
            verified_at=None,
        )

        with self.assertRaises(HTTPException) as raised:
            await get_verified_researcher(researcher, self.session)

        self.assertEqual(403, raised.exception.status_code)

    async def test_rejects_participant(self):
        participant = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

        with self.assertRaises(HTTPException) as raised:
            await get_verified_researcher(participant, self.session)

        self.assertEqual(403, raised.exception.status_code)
        self.session.get.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
