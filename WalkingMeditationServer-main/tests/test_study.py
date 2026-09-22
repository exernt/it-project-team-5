import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError

from route.dependencies import get_verified_researcher, require_study_owner
from route.models import (
    CreateStudyRequest,
    EditStudyRequest,
    JoinStudyRequest,
    SwapStudyGroupRequest,
)
from database.models import (
    ConsentForm,
    Feedback,
    FeedbackCategory,
    Questionnaire,
    ResearcherProfile,
    Role,
    Study,
    StudyCreatedBy,
    StudyGroup,
    StudyRegistration,
    StudyStatus,
    User,
)
from route.studies import (
    activate_study,
    close_study,
    create_study,
    edit_study,
    get_current_study_script_parameters,
    get_consent_form,
    get_study,
    get_study_status,
    join_study,
    leave_study,
    list_my_studies,
    list_studies,
    list_study_feedback,
    publish_study,
    swap_study_group,
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
        self.assertEqual("", response.description)
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
        self.session.execute = AsyncMock()
        self.session.scalar = AsyncMock(side_effect=[None, 0, 0])
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
        self.assertEqual(StudyGroup.CONTROL, registration.study_group)
        self.assertEqual(self.user.id, response.user_id)
        self.assertEqual(self.study.id, response.study_id)
        self.assertIsNotNone(response.registered_at)
        self.assertIsNotNone(response.consented_at)
        self.session.commit.assert_awaited_once()

    async def test_assigns_experimental_when_control_has_more_members(self):
        self.session.get.side_effect = [self.study, None]
        self.session.scalar.side_effect = [None, 3, 2]

        await join_study(
            self.study.id,
            JoinStudyRequest(agrees_to_consent=True),
            self.user,
            self.session,
        )

        registration = self.session.add.call_args.args[0]
        self.assertEqual(StudyGroup.EXPERIMENTAL, registration.study_group)
        self.assertEqual(2, self.session.execute.await_count)
        self.assertEqual(3, self.session.scalar.await_count)

    async def test_rejects_joining_another_current_study(self):
        other_study_id = uuid4()
        self.session.get.side_effect = [self.study, None]
        self.session.scalar.side_effect = [other_study_id]

        with self.assertRaises(HTTPException) as raised:
            await join_study(
                self.study.id,
                JoinStudyRequest(agrees_to_consent=True),
                self.user,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)
        self.assertEqual(
            "You are already participating in another study",
            raised.exception.detail,
        )
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_allows_joining_when_previous_study_is_not_current(self):
        # The enrollment query excludes withdrawn registrations and studies
        # that have reached the closed state, represented here by no result.
        self.session.get.side_effect = [self.study, None]
        self.session.scalar.side_effect = [None, 0, 0]

        response = await join_study(
            self.study.id,
            JoinStudyRequest(agrees_to_consent=True),
            self.user,
            self.session,
        )

        self.assertEqual(self.study.id, response.study_id)
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
            study_group=StudyGroup.CONTROL,
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


class ListStudiesFilterTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalars = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_no_filter_returns_all_studies(self):
        studies = [
            Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.DRAFT),
            Study(id=uuid4(), name="Evening walks", duration=7, status=StudyStatus.RECRUITING),
        ]
        self.session.scalars.return_value = studies

        response = await list_studies(self.user, self.session, status_filter=None)

        self.assertEqual(2, len(response.studies))

    async def test_filter_by_recruiting_only_matches_recruiting(self):
        recruiting_only = [
            Study(id=uuid4(), name="Evening walks", duration=7, status=StudyStatus.RECRUITING),
        ]
        self.session.scalars.return_value = recruiting_only

        response = await list_studies(
            self.user, self.session, status_filter=StudyStatus.RECRUITING
        )

        self.assertEqual(1, len(response.studies))
        self.assertEqual("recruiting", response.studies[0].status)

    async def test_filter_with_no_matches_returns_empty_list(self):
        self.session.scalars.return_value = []

        response = await list_studies(
            self.user, self.session, status_filter=StudyStatus.CLOSED
        )

        self.assertEqual(0, len(response.studies))


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
                description="A two-week morning walking study.",
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
        self.assertEqual(
            "A two-week morning walking study.",
            response.studies[0].description,
        )
        self.assertEqual("Evening walks", response.studies[1].name)

    async def test_returns_empty_list_when_no_studies(self):
        self.session.scalars.return_value = []

        response = await list_studies(self.user, self.session)

        self.assertEqual(0, len(response.studies))

class ListMyStudiesTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.execute = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_returns_registered_studies(self):
        study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            description="A mindful morning walking study.",
            status=StudyStatus.RECRUITING,
        )
        registration = StudyRegistration(
            user_id=self.user.id,
            study_id=study.id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            withdrawn_at=None,
            study_group=StudyGroup.CONTROL,
        )
        result_mock = MagicMock()
        result_mock.all.return_value = [(registration, study)]
        self.session.execute.return_value = result_mock

        response = await list_my_studies(self.user, self.session)

        self.assertEqual(1, len(response.studies))
        self.assertEqual(study.id, response.studies[0].study_id)
        self.assertEqual("Morning walks", response.studies[0].name)
        self.assertEqual(
            "A mindful morning walking study.",
            response.studies[0].description,
        )
        self.assertEqual("recruiting", response.studies[0].status)
        self.assertEqual("control", response.studies[0].study_group)
        self.assertIsNone(response.studies[0].withdrawn_at)

    async def test_returns_empty_list_when_no_registrations(self):
        result_mock = MagicMock()
        result_mock.all.return_value = []
        self.session.execute.return_value = result_mock

        response = await list_my_studies(self.user, self.session)

        self.assertEqual(0, len(response.studies))

    async def test_includes_withdrawn_registrations(self):
        study = Study(
            id=uuid4(),
            name="Evening walks",
            duration=7,
            status=StudyStatus.CLOSED,
        )
        registration = StudyRegistration(
            user_id=self.user.id,
            study_id=study.id,
            registered_at=datetime.now(timezone.utc) - timedelta(days=10),
            consented_at=datetime.now(timezone.utc) - timedelta(days=10),
            withdrawn_at=datetime.now(timezone.utc) - timedelta(days=1),
            study_group=StudyGroup.EXPERIMENTAL,
        )
        result_mock = MagicMock()
        result_mock.all.return_value = [(registration, study)]
        self.session.execute.return_value = result_mock

        response = await list_my_studies(self.user, self.session)

        self.assertEqual(1, len(response.studies))
        self.assertIsNotNone(response.studies[0].withdrawn_at)


class CurrentStudyScriptParametersTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.execute = AsyncMock()
        self.user = User(
            id=uuid4(),
            email="participant@example.com",
            password_hash="hash",
            role=Role.PARTICIPANT,
        )

    async def test_returns_parameters_for_current_study(self):
        study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            status=StudyStatus.ACTIVE,
            script_generation_parameters="Use gratitude-focused language.",
        )
        registration = StudyRegistration(
            user_id=self.user.id,
            study_id=study.id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            study_group=StudyGroup.EXPERIMENTAL,
        )
        result_mock = MagicMock()
        result_mock.first.return_value = (registration, study)
        self.session.execute.return_value = result_mock

        response = await get_current_study_script_parameters(self.user, self.session)

        self.assertTrue(response.participating)
        self.assertEqual(study.id, response.study_id)
        self.assertEqual("active", response.study_status)
        self.assertEqual(
            "Use gratitude-focused language.",
            response.script_generation_parameters,
        )

    async def test_returns_non_participating_when_no_current_study(self):
        result_mock = MagicMock()
        result_mock.first.return_value = None
        self.session.execute.return_value = result_mock

        response = await get_current_study_script_parameters(self.user, self.session)

        self.assertFalse(response.participating)
        self.assertIsNone(response.study_id)
        self.assertEqual("", response.script_generation_parameters)

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
        study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            description="Walk mindfully each morning for two weeks.",
            status=StudyStatus.RECRUITING,
        )
        self.session.get.return_value = study

        response = await get_study(study.id, self.user, self.session)

        self.assertEqual(study.id, response.id)
        self.assertEqual("Morning walks", response.name)
        self.assertEqual(14, response.duration)
        self.assertEqual("Walk mindfully each morning for two weeks.", response.description)

    async def test_missing_study_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await get_study(uuid4(), self.user, self.session)

        self.assertEqual(404, raised.exception.status_code)


class GetStudyStatusTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.owner = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            status=StudyStatus.ACTIVE,
        )
        self.session.get.return_value = self.study

    async def test_returns_demographics_grouped_by_assignment(self):
        control_user_id = uuid4()
        experimental_user_id = uuid4()
        query_result = MagicMock()
        query_result.all.return_value = [
            (StudyGroup.CONTROL, control_user_id, "woman", 28),
            (StudyGroup.EXPERIMENTAL, experimental_user_id, None, None),
        ]
        self.session.execute.return_value = query_result

        response = await get_study_status(
            self.study.id,
            self.owner,
            self.session,
        )

        self.assertEqual("Morning walks", response.name)
        self.assertEqual(14, response.duration)
        self.assertEqual(control_user_id, response.groups["control"][0].user_id)
        self.assertEqual("woman", response.groups["control"][0].gender)
        self.assertEqual(28, response.groups["control"][0].age)
        self.assertEqual(
            experimental_user_id,
            response.groups["experimental"][0].user_id,
        )
        self.assertIsNone(response.groups["experimental"][0].gender)
        self.assertIsNone(response.groups["experimental"][0].age)

        statement = self.session.execute.await_args.args[0]
        self.assertIn("withdrawn_at IS NULL", str(statement))

    async def test_returns_both_groups_when_study_has_no_participants(self):
        query_result = MagicMock()
        query_result.all.return_value = []
        self.session.execute.return_value = query_result

        response = await get_study_status(
            self.study.id,
            self.owner,
            self.session,
        )

        self.assertEqual([], response.groups["control"])
        self.assertEqual([], response.groups["experimental"])


class SwapStudyGroupTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.owner = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.study_id = uuid4()
        self.user_id = uuid4()

    async def test_moves_registered_user_to_target_group(self):
        registration = StudyRegistration(
            user_id=self.user_id,
            study_id=self.study_id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            study_group=StudyGroup.CONTROL,
        )
        self.session.get.return_value = registration

        response = await swap_study_group(
            self.study_id,
            SwapStudyGroupRequest(
                user_id=self.user_id,
                target_group="experimental",
            ),
            self.owner,
            self.session,
        )

        self.assertEqual(StudyGroup.EXPERIMENTAL, registration.study_group)
        self.assertEqual("experimental", response.group)
        self.assertEqual(self.user_id, response.user_id)
        self.session.commit.assert_awaited_once()

    async def test_rejects_unknown_target_group(self):
        with self.assertRaises(HTTPException) as raised:
            await swap_study_group(
                self.study_id,
                SwapStudyGroupRequest(
                    user_id=self.user_id,
                    target_group="placebo",
                ),
                self.owner,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)
        self.session.get.assert_not_awaited()
        self.session.commit.assert_not_awaited()

    async def test_rejects_user_not_registered_for_study(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await swap_study_group(
                self.study_id,
                SwapStudyGroupRequest(
                    user_id=self.user_id,
                    target_group="control",
                ),
                self.owner,
                self.session,
            )

        self.assertEqual(404, raised.exception.status_code)
        self.session.commit.assert_not_awaited()


class EditStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.owner = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.study = Study(
            id=uuid4(),
            name="Morning walks",
            duration=14,
            status=StudyStatus.DRAFT,
        )
        self.session.get.return_value = self.study

    async def test_edits_and_normalizes_name(self):
        response = await edit_study(
            self.study.id,
            EditStudyRequest(column="name", value="  Evening walks  "),
            self.owner,
            self.session,
        )

        self.assertEqual("Evening walks", self.study.name)
        self.assertEqual("Evening walks", response.name)
        self.session.commit.assert_awaited_once()

    async def test_edits_duration(self):
        response = await edit_study(
            self.study.id,
            EditStudyRequest(column="duration", value=21),
            self.owner,
            self.session,
        )

        self.assertEqual(21, self.study.duration)
        self.assertEqual(21, response.duration)
        self.session.commit.assert_awaited_once()

    async def test_edits_script_generation_parameters(self):
        response = await edit_study(
            self.study.id,
            EditStudyRequest(
                column="script_generation_parameters",
                value="  Keep each narration under 80 words.  ",
            ),
            self.owner,
            self.session,
        )

        self.assertEqual(
            "Keep each narration under 80 words.",
            self.study.script_generation_parameters,
        )
        self.assertEqual(
            "Keep each narration under 80 words.",
            response.script_generation_parameters,
        )
        self.session.commit.assert_awaited_once()

    async def test_edits_and_normalizes_description(self):
        response = await edit_study(
            self.study.id,
            EditStudyRequest(
                column="description",
                value="  Walk mindfully each morning for two weeks.  ",
            ),
            self.owner,
            self.session,
        )

        self.assertEqual(
            "Walk mindfully each morning for two weeks.",
            self.study.description,
        )
        self.assertEqual(self.study.description, response.description)
        self.session.commit.assert_awaited_once()

    async def test_rejects_description_over_10000_characters(self):
        with self.assertRaises(HTTPException) as raised:
            await edit_study(
                self.study.id,
                EditStudyRequest(column="description", value="x" * 10_001),
                self.owner,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)
        self.session.commit.assert_not_awaited()

    async def test_allows_clearing_script_generation_parameters(self):
        self.study.script_generation_parameters = "Use a body scan."

        response = await edit_study(
            self.study.id,
            EditStudyRequest(column="script_generation_parameters", value="   "),
            self.owner,
            self.session,
        )

        self.assertEqual("", self.study.script_generation_parameters)
        self.assertEqual("", response.script_generation_parameters)

    async def test_rejects_unknown_column(self):
        with self.assertRaises(HTTPException) as raised:
            await edit_study(
                self.study.id,
                EditStudyRequest(column="location", value="Melbourne"),
                self.owner,
                self.session,
            )

        self.assertEqual(400, raised.exception.status_code)
        self.session.commit.assert_not_awaited()

    async def test_rejects_protected_column(self):
        with self.assertRaises(HTTPException) as raised:
            await edit_study(
                self.study.id,
                EditStudyRequest(column="status", value="active"),
                self.owner,
                self.session,
            )

        self.assertEqual(400, raised.exception.status_code)
        self.session.commit.assert_not_awaited()

    async def test_rejects_wrong_value_type(self):
        with self.assertRaises(HTTPException) as raised:
            await edit_study(
                self.study.id,
                EditStudyRequest(column="duration", value="21"),
                self.owner,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)
        self.session.commit.assert_not_awaited()

    async def test_rejects_invalid_value(self):
        with self.assertRaises(HTTPException) as raised:
            await edit_study(
                self.study.id,
                EditStudyRequest(column="name", value="   "),
                self.owner,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)
        self.session.commit.assert_not_awaited()


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
        self.session.execute = AsyncMock()
        self.session.scalar = AsyncMock(side_effect=[None, 0, 0])
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
            study_group=StudyGroup.CONTROL,
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
            study_group=StudyGroup.CONTROL,
        )
        self.session.get.return_value = registration

        with self.assertRaises(HTTPException) as raised:
            await leave_study(self.study_id, self.user, self.session)

        self.assertEqual(409, raised.exception.status_code)


class RejoinStudyTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.session.scalar = AsyncMock(return_value=None)
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
            study_group=StudyGroup.EXPERIMENTAL,
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
        self.assertEqual(StudyGroup.EXPERIMENTAL, withdrawn_registration.study_group)
        self.session.add.assert_not_called()  # reactivated, not re-inserted
        self.session.commit.assert_awaited_once()

    async def test_rejoining_still_blocked_if_not_withdrawn(self):
        active_registration = StudyRegistration(
            user_id=self.user.id,
            study_id=self.study.id,
            registered_at=datetime.now(timezone.utc),
            consented_at=datetime.now(timezone.utc),
            withdrawn_at=None,
            study_group=StudyGroup.CONTROL,
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

# Old questionnaire unit tests - to modify/delete
# class SetQuestionTests(unittest.IsolatedAsyncioTestCase):
#     def setUp(self):
#         self.session = MagicMock()
#         self.session.execute = AsyncMock()
#         self.session.scalar = AsyncMock()
#         self.session.commit = AsyncMock()
#         self.study_id = uuid4()
#         self.owner = User(
#             id=uuid4(),
#             email="researcher@example.com",
#             password_hash="hash",
#             role=Role.RESEARCHER,
#         )

#     async def test_stores_questionnaire_json_with_first_id(self):
#         self.session.scalar.return_value = None

#         response = await set_question(
#             self.study_id,
#             SetQuestionRequest(title="Weekly check-in"),
#             self.owner,
#             self.session,
#         )

#         questionnaire = self.session.add.call_args.args[0]
#         self.assertIsInstance(questionnaire, Questionnaire)
#         self.assertEqual(1, questionnaire.id)
#         self.assertEqual(self.study_id, questionnaire.study_id)
#         self.assertEqual({"title": "Weekly check-in"}, questionnaire.data)
#         self.assertEqual(questionnaire.data, response.data)
#         self.session.execute.assert_awaited_once()
#         self.session.commit.assert_awaited_once()

#     async def test_increments_id_within_study(self):
#         self.session.scalar.return_value = 2

#         response = await set_question(
#             self.study_id,
#             SetQuestionRequest(title="Follow-up"),
#             self.owner,
#             self.session,
#         )

#         self.assertEqual(3, response.id)

#     def test_rejects_fields_other_than_title(self):
#         with self.assertRaises(ValidationError):
#             SetQuestionRequest(title="Check-in", questions=[])

class ListStudyFeedbackTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.scalars = AsyncMock()
        self.researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )
        self.study = Study(id=uuid4(), name="Morning walks", duration=14, status=StudyStatus.RECRUITING)

    async def test_returns_feedback_for_owned_study(self):
        ownership = StudyCreatedBy(researcher_id=self.researcher.id, study_id=self.study.id)
        self.session.get.side_effect = [self.study, ownership]
        feedback_item = Feedback(
            id=uuid4(),
            user_id=uuid4(),
            study_id=self.study.id,
            category=FeedbackCategory.STUDY,
            rating=4,
            comment="Good experience",
            created_at=datetime.now(timezone.utc),
        )
        self.session.scalars.return_value = [feedback_item]

        response = await list_study_feedback(self.study.id, self.researcher, self.session)

        self.assertEqual(1, len(response.feedback))
        self.assertEqual(4, response.feedback[0].rating)
        self.assertEqual("study", response.feedback[0].category)



if __name__ == "__main__":
    unittest.main()
