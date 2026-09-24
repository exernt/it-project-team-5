import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy.exc import IntegrityError

from database.models import (
    Questionnaire,
    QuestionnaireResponse,
    QuestionnaireStatus,
    Role,
    Study,
    StudyCreatedBy,
    StudyGroup,
    StudyQuestionnaire,
    StudyRegistration,
    StudyStatus,
    User,
)
from route.models import IssueQuestionnaireRequest, SubmitAnswersRequest
from route.study_questionnaires import (
    get_issued_questionnaire,
    get_responses,
    issue_questionnaire,
    list_issued_questionnaires,
    revoke_questionnaire,
    submit_response,
)


MINIMAL_DEFINITION = {
    "title": "Weekly check-in",
    "questions": [
        {
            "id": "q1",
            "type": "text",
            "prompt": "How are you feeling today?",
            "required": True,
        }
    ],
}


def result_with(*, rows=None, scalars=None):
    result = MagicMock()
    result.all.return_value = rows or []
    result.scalars.return_value.all.return_value = scalars or []
    return result


def make_user(role=Role.PARTICIPANT):
    return User(id=uuid4(), email=f"{uuid4()}@example.com", password_hash="hash", role=role)


def make_questionnaire(researcher_id, status=QuestionnaireStatus.PUBLISHED):
    return Questionnaire(
        id=uuid4(),
        title="Weekly check-in",
        researcher_id=researcher_id,
        status=status,
        definition=MINIMAL_DEFINITION,
        created_on=datetime.now(timezone.utc),
    )


def make_study(study_id=None, status=StudyStatus.ACTIVE):
    return Study(
        id=study_id or uuid4(),
        name="Morning walks",
        duration=14,
        status=status,
    )


def make_issuance(study_id, questionnaire_id, round_number=1, revoked_at=None):
    return StudyQuestionnaire(
        id=uuid4(),
        study_id=study_id,
        questionnaire_id=questionnaire_id,
        round_number=round_number,
        issued_at=datetime.now(timezone.utc),
        revoked_at=revoked_at,
    )


def make_registration(user_id, study_id, withdrawn_at=None):
    return StudyRegistration(
        user_id=user_id,
        study_id=study_id,
        registered_at=datetime.now(timezone.utc),
        consented_at=datetime.now(timezone.utc),
        withdrawn_at=withdrawn_at,
        study_group=StudyGroup.CONTROL,
    )


class IssueQuestionnaireTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock(return_value=result_with())
        self.session.scalar = AsyncMock()
        self.session.commit = AsyncMock()
        self.owner = make_user(Role.RESEARCHER)
        self.study = make_study()
        self.questionnaire = make_questionnaire(self.owner.id)

    async def test_creates_next_round(self):
        self.session.get.side_effect = [self.questionnaire, self.study]
        self.session.scalar.return_value = 2

        response = await issue_questionnaire(
            self.study.id,
            IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id),
            self.owner,
            self.session,
        )

        added = self.session.add.call_args.args[0]
        self.assertEqual(3, added.round_number)
        self.assertEqual(3, response.round_number)
        self.assertEqual("Weekly check-in", response.title)
        self.session.commit.assert_awaited_once()

    async def test_each_issue_creates_a_new_round(self):
        self.session.get.side_effect = [self.questionnaire, self.study]
        self.session.scalar.return_value = 1

        await issue_questionnaire(
            self.study.id,
            IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id),
            self.owner,
            self.session,
        )

        self.assertEqual(2, self.session.add.call_args.args[0].round_number)

    async def test_rejects_questionnaire_owned_by_someone_else(self):
        self.questionnaire.researcher_id = uuid4()
        self.session.get.return_value = self.questionnaire

        with self.assertRaises(HTTPException) as raised:
            await issue_questionnaire(
                self.study.id,
                IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id),
                self.owner,
                self.session,
            )

        self.assertEqual(403, raised.exception.status_code)

    async def test_rejects_unpublished_questionnaire(self):
        self.questionnaire.status = QuestionnaireStatus.DRAFT
        self.session.get.return_value = self.questionnaire

        with self.assertRaises(HTTPException) as raised:
            await issue_questionnaire(
                self.study.id,
                IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id),
                self.owner,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)

    async def test_rejects_inactive_study(self):
        self.study.status = StudyStatus.CLOSED
        self.session.get.side_effect = [self.questionnaire, self.study]

        with self.assertRaises(HTTPException) as raised:
            await issue_questionnaire(
                self.study.id,
                IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id),
                self.owner,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)


class ParticipantQuestionnaireTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.session.scalar = AsyncMock(return_value=None)
        self.participant = make_user()
        self.study = make_study()
        self.questionnaire = make_questionnaire(uuid4())
        self.issuance = make_issuance(self.study.id, self.questionnaire.id, 2)
        self.registration = make_registration(self.participant.id, self.study.id)

    async def test_active_participant_lists_open_rounds(self):
        self.session.get.return_value = self.registration
        self.session.execute.return_value = result_with(
            rows=[(self.issuance, self.questionnaire, None)]
        )

        response = await list_issued_questionnaires(
            self.study.id, self.participant, self.session
        )

        self.assertEqual(1, len(response.issued_questionnaires))
        self.assertEqual(2, response.issued_questionnaires[0].round_number)
        self.assertFalse(response.issued_questionnaires[0].has_responded)

    async def test_unrelated_user_cannot_list_rounds(self):
        self.session.get.side_effect = [None, None]

        with self.assertRaises(HTTPException) as raised:
            await list_issued_questionnaires(
                self.study.id, self.participant, self.session
            )

        self.assertEqual(403, raised.exception.status_code)

    async def test_participant_retrieves_definition_for_open_round(self):
        self.session.get.side_effect = [
            self.registration,
            self.issuance,
            self.questionnaire,
            self.study,
        ]

        response = await get_issued_questionnaire(
            self.study.id, self.issuance.id, self.participant, self.session
        )

        self.assertEqual(MINIMAL_DEFINITION, response.definition)
        self.assertEqual(2, response.round_number)


class RevokeQuestionnaireTests(unittest.IsolatedAsyncioTestCase):
    async def test_owner_revokes_only_the_selected_round(self):
        session = MagicMock()
        session.get = AsyncMock()
        session.commit = AsyncMock()
        owner = make_user(Role.RESEARCHER)
        study = make_study()
        questionnaire = make_questionnaire(owner.id)
        issuance = make_issuance(study.id, questionnaire.id, 2)
        session.get.side_effect = [issuance, questionnaire]

        response = await revoke_questionnaire(study.id, issuance.id, owner, session)

        self.assertIsNotNone(issuance.revoked_at)
        self.assertEqual(2, response.round_number)


class SubmitResponseTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.scalar = AsyncMock(return_value=None)
        self.session.commit = AsyncMock()
        self.session.rollback = AsyncMock()
        self.participant = make_user()
        self.study = make_study()
        self.questionnaire = make_questionnaire(uuid4())
        self.issuance = make_issuance(self.study.id, self.questionnaire.id, 3)
        self.registration = make_registration(self.participant.id, self.study.id)

    def set_valid_gets(self):
        self.session.get.side_effect = [
            self.issuance,
            self.registration,
            self.study,
            self.questionnaire,
        ]

    async def test_active_participant_submits_once_per_round(self):
        self.set_valid_gets()
        payload = SubmitAnswersRequest(answers={"q1": "Doing well"})

        response = await submit_response(
            self.study.id, self.issuance.id, payload, self.participant, self.session
        )

        added = self.session.add.call_args.args[0]
        self.assertEqual(self.issuance.id, added.study_questionnaire_id)
        self.assertEqual(self.participant.id, added.respondent_id)
        self.assertEqual(added.id, response.id)

    async def test_nonparticipant_cannot_submit(self):
        self.session.get.side_effect = [self.issuance, None]

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study.id,
                self.issuance.id,
                SubmitAnswersRequest(answers={"q1": "No"}),
                self.participant,
                self.session,
            )

        self.assertEqual(403, raised.exception.status_code)
        self.session.add.assert_not_called()

    async def test_withdrawn_participant_cannot_submit(self):
        self.registration.withdrawn_at = datetime.now(timezone.utc)
        self.session.get.side_effect = [self.issuance, self.registration]

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study.id,
                self.issuance.id,
                SubmitAnswersRequest(answers={"q1": "No"}),
                self.participant,
                self.session,
            )

        self.assertEqual(403, raised.exception.status_code)

    async def test_duplicate_response_returns_conflict(self):
        self.set_valid_gets()
        self.session.scalar.return_value = uuid4()

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study.id,
                self.issuance.id,
                SubmitAnswersRequest(answers={"q1": "Again"}),
                self.participant,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)

    async def test_database_constraint_closes_duplicate_race(self):
        self.set_valid_gets()
        self.session.commit.side_effect = IntegrityError("insert", {}, Exception("duplicate"))

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study.id,
                self.issuance.id,
                SubmitAnswersRequest(answers={"q1": "Racing"}),
                self.participant,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)
        self.session.rollback.assert_awaited_once()

    async def test_required_answer_is_enforced(self):
        self.set_valid_gets()

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study.id,
                self.issuance.id,
                SubmitAnswersRequest(answers={}),
                self.participant,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)


class GetResponsesTests(unittest.IsolatedAsyncioTestCase):
    async def test_owner_gets_responses_for_one_round(self):
        session = MagicMock()
        session.get = AsyncMock()
        session.execute = AsyncMock()
        owner = make_user(Role.RESEARCHER)
        study = make_study()
        questionnaire = make_questionnaire(owner.id)
        issuance = make_issuance(study.id, questionnaire.id, 1)
        answer = QuestionnaireResponse(
            id=uuid4(),
            study_questionnaire_id=issuance.id,
            respondent_id=uuid4(),
            answers={"q1": "Good"},
            submitted_on=datetime.now(timezone.utc),
        )
        session.get.return_value = issuance
        session.execute.return_value = result_with(scalars=[answer])

        response = await get_responses(study.id, issuance.id, owner, session)

        self.assertEqual(1, len(response.responses))


class RouterRegistrationTests(unittest.TestCase):
    def test_questionnaire_routes_are_registered(self):
        from main import app

        paths = {route.path for route in app.routes}
        self.assertIn("/questionnaires", paths)
        self.assertIn("/studies/{study_id}/questionnaires", paths)
        self.assertIn(
            "/studies/{study_id}/questionnaires/{study_questionnaire_id}/responses",
            paths,
        )


if __name__ == "__main__":
    unittest.main()
