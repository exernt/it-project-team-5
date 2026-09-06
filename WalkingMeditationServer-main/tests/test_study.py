import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError

from route.dependencies import get_verified_researcher
from route.models import CreateStudyRequest
from route.persistence.orm_models import (
    ResearcherProfile,
    Role,
    Study,
    StudyCreatedBy,
    StudyRegistration,
    User,
)
from route.studies import create_study, join_study


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

        added_records = self.session.add_all.call_args.args[0]
        added_study = added_records[0]
        ownership = added_records[1]
        self.assertIsInstance(added_study, Study)
        self.assertIsInstance(ownership, StudyCreatedBy)
        self.assertEqual("Morning walks", added_study.name)
        self.assertEqual(14, added_study.duration)
        self.assertEqual(self.researcher.id, ownership.researcher_id)
        self.assertEqual(added_study.id, ownership.study_id)
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


class JoinStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.session.rollback = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )
        self.study = Study(id=uuid4(), name="Morning walks", duration=14)

    async def test_joins_existing_study(self):
        self.session.get.side_effect = [self.study, None]

        response = await join_study(self.study.id, self.user, self.session)

        registration = self.session.add.call_args.args[0]
        self.assertIsInstance(registration, StudyRegistration)
        self.assertEqual(self.user.id, registration.user_id)
        self.assertEqual(self.study.id, registration.study_id)
        self.assertEqual(self.user.id, response.user_id)
        self.assertEqual(self.study.id, response.study_id)
        self.assertIsNotNone(response.registered_at)
        self.session.commit.assert_awaited_once()

    async def test_missing_study_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await join_study(uuid4(), self.user, self.session)

        self.assertEqual(404, raised.exception.status_code)
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_duplicate_registration_returns_409(self):
        existing = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study.id,
            registered_at=datetime.now(timezone.utc),
        )
        self.session.get.side_effect = [self.study, existing]

        with self.assertRaises(HTTPException) as raised:
            await join_study(self.study.id, self.user, self.session)

        self.assertEqual(409, raised.exception.status_code)
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_duplicate_registration_race_returns_409(self):
        self.session.get.side_effect = [self.study, None]
        self.session.commit.side_effect = IntegrityError(
            "insert",
            {},
            Exception("duplicate"),
        )

        with self.assertRaises(HTTPException) as raised:
            await join_study(self.study.id, self.user, self.session)

        self.assertEqual(409, raised.exception.status_code)
        self.session.rollback.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
