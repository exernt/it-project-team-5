import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError

from route.dependencies import get_verified_researcher, require_study_owner
from route.models import CreateStudyRequest, JoinStudyRequest, SetQuestionRequest
from database.models import (
    ConsentForm,
    Questionnaire,
    ResearcherProfile,
    Role,
    Study,
    StudyCreatedBy,
    StudyRegistration,
    StudyStatus,
    User,
)
from route.studies import (
    activate_study,
    close_study,
    create_study,
    get_consent_form,
    get_study,
    join_study,
    leave_study,
    list_studies,
    publish_study,
    set_question,
)

class CreateStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.flush = AsyncMock()
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
        added_records = self.session.add_all.call_args.args[0]
        ownership = added_records[0]
        consent_form = added_records[1]
        self.assertIsInstance(added_study, Study)
        self.assertIsInstance(ownership, StudyCreatedBy)
        self.assertIsInstance(consent_form, ConsentForm)
        self.assertEqual("Morning walks", added_study.name)
        self.assertEqual(14, added_study.duration)
        self.assertEqual(self.researcher.id, ownership.researcher_id)
        self.assertEqual(added_study.id, ownership.study_id)
        self.assertEqual(added_study.id, consent_form.study_id)
        self.assertEqual(added_study.id, response.id)
        self.assertEqual("Morning walks", response.name)
        self.assertEqual(14, response.duration)
        self.session.flush.assert_awaited_once()
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
        self.study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            status=StudyStatus.RECRUITING,
        )

    async def test_joins_existing_study(self):
        self.session.get.side_effect = [self.study, None]

        response = await join_study(
            self.study.id,
            JoinStudyRequest(agrees_to_consent=True),
            self.user,
            self.session,
        )

        registration = self.session.add.call_args.args[0]
        self.assertIsInstance(registration, StudyRegistration)
        self.assertEqual(self.user.id, registration.user_id)
        self.assertEqual(self.study.id, registration.study_id)
        self.assertEqual(self.user.id, response.user_id)
        self.assertEqual(self.study.id, response.study_id)
        self.assertIsNotNone(response.registered_at)
        self.assertIsNotNone(response.consented_at)
        self.session.commit.assert_awaited_once()

    async def test_missing_study_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                uuid4(),
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

        self.assertEqual(404, raised.exception.status_code)
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_duplicate_registration_returns_409(self):
        existing = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study.id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
        )
        self.session.get.side_effect = [self.study, existing]

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                self.study.id,
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

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
            await join_study(
                self.study.id,
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)
        self.session.rollback.assert_awaited_once()


class JoinStudyConsentTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )
        self.study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            status=StudyStatus.RECRUITING,
        )

    async def test_rejects_joining_without_consent(self):
        self.session.get.return_value = self.study

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                self.study.id,
                JoinStudyRequest(agrees_to_consent=False),
                self.user,
                self.session,
            )

        self.assertEqual(400, raised.exception.status_code)
        self.session.add.assert_not_called()


class GetConsentFormTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_returns_existing_consent_form(self):
        study_id = uuid4()
        consent_form = ConsentForm(
            study_id=study_id,
            body_text="Placeholder consent text — to be finalized.",
            version=1,
        )
        self.session.get.return_value = consent_form

        response = await get_consent_form(study_id, self.user, self.session)

        self.assertEqual(study_id, response.study_id)
        self.assertEqual("Placeholder consent text — to be finalized.", response.body_text)
        self.assertEqual(1, response.version)

    async def test_missing_consent_form_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await get_consent_form(uuid4(), self.user, self.session)

        self.assertEqual(404, raised.exception.status_code)


class ListStudiesTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalars = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_returns_all_studies(self):
        studies = [
            Study(
                id=uuid4(),
                name="Morning walks",
                duration=14,
                status=StudyStatus.RECRUITING,
            ),
            Study(
                id=uuid4(),
                name="Evening walks",
                duration=7,
                status=StudyStatus.RECRUITING,
            ),
        ]
        self.session.scalars.return_value = studies

        response = await list_studies(self.user, self.session)

        self.assertEqual(2, len(response.studies))
        self.assertEqual("Morning walks", response.studies[0].name)
        self.assertEqual("Evening walks", response.studies[1].name)

    async def test_returns_empty_list_when_no_studies(self):
        self.session.scalars.return_value = []

        response = await list_studies(self.user, self.session)

        self.assertEqual(0, len(response.studies))


class GetStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_returns_existing_study(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.RECRUITING)
        self.session.get.return_value = study

        response = await get_study(study.id, self.user, self.session)

        self.assertEqual(study.id, response.id)
        self.assertEqual("Morning walks", response.name)
        self.assertEqual(14, response.duration)

    async def test_missing_study_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await get_study(uuid4(), self.user, self.session)

        self.assertEqual(404, raised.exception.status_code)

class StudyOwnerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.DRAFT)

    async def test_allows_actual_owner(self):
        ownership = StudyCreatedBy(researcher_id=self.researcher.id, study_id=self.study.id)
        self.session.get.side_effect = [self.study, ownership]

        result = await require_study_owner(self.study.id, self.researcher, self.session)

        self.assertIs(self.researcher, result)

    async def test_rejects_non_owner(self):
        other_researcher_id = uuid4()
        ownership = StudyCreatedBy(researcher_id=other_researcher_id, study_id=self.study.id)
        self.session.get.side_effect = [self.study, ownership]

        with self.assertRaises(HTTPException) as raised:
            await require_study_owner(self.study.id, self.researcher, self.session)

        self.assertEqual(403, raised.exception.status_code)

    async def test_missing_study_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await require_study_owner(uuid4(), self.researcher, self.session)

        self.assertEqual(404, raised.exception.status_code)


class StudyLifecycleTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )

    async def test_publish_moves_draft_to_recruiting(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.DRAFT)
        self.session.get.return_value = study

        response = await publish_study(study.id, self.researcher, self.session)

        self.assertEqual(StudyStatus.RECRUITING, study.status)
        self.assertEqual("recruiting", response.status)
        self.session.commit.assert_awaited_once()

    async def test_publish_rejects_non_draft(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.RECRUITING)
        self.session.get.return_value = study

        with self.assertRaises(HTTPException) as raised:
            await publish_study(study.id, self.researcher, self.session)

        self.assertEqual(409, raised.exception.status_code)

    async def test_activate_moves_recruiting_to_active(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.RECRUITING)
        self.session.get.return_value = study

        response = await activate_study(study.id, self.researcher, self.session)

        self.assertEqual(StudyStatus.ACTIVE, study.status)
        self.assertEqual("active", response.status)

    async def test_activate_rejects_draft(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.DRAFT)
        self.session.get.return_value = study

        with self.assertRaises(HTTPException) as raised:
            await activate_study(study.id, self.researcher, self.session)

        self.assertEqual(409, raised.exception.status_code)

    async def test_close_allowed_from_recruiting(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.RECRUITING)
        self.session.get.return_value = study

        response = await close_study(study.id, self.researcher, self.session)

        self.assertEqual(StudyStatus.CLOSED, study.status)
        self.assertEqual("closed", response.status)

    async def test_close_allowed_from_active(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.ACTIVE)
        self.session.get.return_value = study

        response = await close_study(study.id, self.researcher, self.session)

        self.assertEqual(StudyStatus.CLOSED, study.status)
        self.assertEqual("closed", response.status)

    async def test_close_rejects_draft(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.DRAFT)
        self.session.get.return_value = study

        with self.assertRaises(HTTPException) as raised:
            await close_study(study.id, self.researcher, self.session)

        self.assertEqual(409, raised.exception.status_code)


class JoinStudyStatusGateTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_rejects_joining_draft_study(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.DRAFT)
        self.session.get.return_value = study

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                study.id,
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)

    async def test_rejects_joining_closed_study(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.CLOSED)
        self.session.get.return_value = study

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                study.id,
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)

    async def test_allows_joining_recruiting_study(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.RECRUITING)
        self.session.get.side_effect = [study, None]

        response = await join_study(
            study.id,
            JoinStudyRequest(agrees_to_consent=True),
            self.user,
            self.session,
        )

        self.assertEqual(self.user.id, response.user_id)

class LeaveStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )
        self.study_id = uuid4()

    async def test_leaves_active_registration(self):
        registration = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study_id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            withdrawn_at=None,
        )
        self.session.get.return_value = registration

        response = await leave_study(self.study_id, self.user, self.session)

        self.assertIsNotNone(registration.withdrawn_at)
        self.assertIsNotNone(response.withdrawn_at)
        self.session.commit.assert_awaited_once()

    async def test_rejects_leaving_when_never_registered(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await leave_study(self.study_id, self.user, self.session)

        self.assertEqual(404, raised.exception.status_code)

    async def test_rejects_leaving_twice(self):
        registration = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study_id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            withdrawn_at=datetime.now(timezone.utc),
        )
        self.session.get.return_value = registration

        with self.assertRaises(HTTPException) as raised:
            await leave_study(self.study_id, self.user, self.session)

        self.assertEqual(409, raised.exception.status_code)


class RejoinStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )
        self.study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            status=StudyStatus.RECRUITING,
        )

    async def test_rejoining_reactivates_withdrawn_registration(self):
        old_registered_at = datetime.now(timezone.utc) - timedelta(days=5)
        withdrawn_registration = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study.id,
            registered_at=old_registered_at,
            consented_at=old_registered_at,
            withdrawn_at=datetime.now(timezone.utc) - timedelta(days=1),
        )
        self.session.get.side_effect = [self.study, withdrawn_registration]

        response = await join_study(
            self.study.id,
            JoinStudyRequest(agrees_to_consent=True),
            self.user,
            self.session,
        )

        self.assertIsNone(withdrawn_registration.withdrawn_at)
        self.assertIsNone(response.withdrawn_at)
        self.assertGreater(withdrawn_registration.registered_at, old_registered_at)
        self.session.add.assert_not_called()  # reactivated, not re-inserted
        self.session.commit.assert_awaited_once()

    async def test_rejoining_still_blocked_if_not_withdrawn(self):
        active_registration = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study.id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            withdrawn_at=None,
        )
        self.session.get.side_effect = [self.study, active_registration]

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                self.study.id,
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)


class SetQuestionTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.execute = AsyncMock()
        self.session.scalar = AsyncMock()
        self.session.commit = AsyncMock()
        self.study_id = uuid4()
        self.owner = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )

    async def test_stores_questionnaire_json_with_first_id(self):
        self.session.scalar.return_value = None

        response = await set_question(
            self.study_id,
            SetQuestionRequest(title="Weekly check-in"),
            self.owner,
            self.session,
        )

        questionnaire = self.session.add.call_args.args[0]
        self.assertIsInstance(questionnaire, Questionnaire)
        self.assertEqual(1, questionnaire.id)
        self.assertEqual(self.study_id, questionnaire.study_id)
        self.assertEqual({"title": "Weekly check-in"}, questionnaire.data)
        self.assertEqual(questionnaire.data, response.data)
        self.session.execute.assert_awaited_once()
        self.session.commit.assert_awaited_once()

    async def test_increments_id_within_study(self):
        self.session.scalar.return_value = 2

        response = await set_question(
            self.study_id,
            SetQuestionRequest(title="Follow-up"),
            self.owner,
            self.session,
        )

        self.assertEqual(3, response.id)

    def test_rejects_fields_other_than_title(self):
        with self.assertRaises(ValidationError):
            SetQuestionRequest(title="Check-in", questions=[])

if __name__ == "__main__":
    unittest.main()
