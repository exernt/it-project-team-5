import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy.exc import IntegrityError

from route.models import IssueQuestionnaireRequest, SubmitAnswersRequest
from database.models import (
    Questionnaire,
    QuestionnaireResponse,
    QuestionnaireStatus,
    Role,
    Study,
    StudyQuestionnaire,
    StudyStatus,
    User,
)
from route.study_questionnaires import (
    get_responses,
    issue_questionnaire,
    list_issued_questionnaires,
    revoke_questionnaire,
    submit_response,
)


MINIMAL_DEFINITION = {
    "title": "Weekly check-in",
    "questions": [
        {"id": "q1", "type": "text", "prompt": "How are you feeling today?"}
    ],
}


def make_execute_result(scalar_one_or_none=None, scalars_all=None):
    """Build a MagicMock standing in for the object session.execute()
    returns, configured for whichever of the two access patterns our
    route code actually uses ((.scalar_one_or_none() or .scalars().all())."""
    result = MagicMock()
    result.scalar_one_or_none.return_value = scalar_one_or_none
    result.scalars.return_value.all.return_value = scalars_all or []
    return result


class IssueQuestionnaireTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.session.commit = AsyncMock()

        self.researcher = User(
            id=uuid4(), email="researcher@example.com", password_hash="hash", role=Role.RESEARCHER
        )
        self.study_id = uuid4()
        self.questionnaire = Questionnaire(
            id=uuid4(),
            title="Weekly check-in",
            researcher_id=self.researcher.id,
            status=QuestionnaireStatus.PUBLISHED,
            definition=MINIMAL_DEFINITION,
        )

    async def test_issues_new_questionnaire(self):
        self.session.get.return_value = self.questionnaire
        self.session.execute.return_value = make_execute_result(scalar_one_or_none=None)
        payload = IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id)

        response = await issue_questionnaire(self.study_id, payload, self.researcher, self.session)

        added = self.session.add.call_args.args[0]
        self.assertIsInstance(added, StudyQuestionnaire)
        self.assertEqual(self.study_id, added.study_id)
        self.assertEqual(self.questionnaire.id, added.questionnaire_id)
        self.assertIsNone(added.revoked_at)
        self.assertEqual(added.id, response.id)
        self.assertEqual(self.study_id, response.study_id)
        self.assertEqual(self.questionnaire.id, response.questionnaire_id)
        self.session.commit.assert_awaited_once()

    async def test_reissuing_reactivates_existing_row_instead_of_duplicating(self):
        old_issued_at = datetime.now(timezone.utc) - timedelta(days=10)
        existing = StudyQuestionnaire(
            id=uuid4(),
            study_id=self.study_id,
            questionnaire_id=self.questionnaire.id,
            issued_at=old_issued_at,
            revoked_at=datetime.now(timezone.utc) - timedelta(days=1),
        )
        self.session.get.return_value = self.questionnaire
        self.session.execute.return_value = make_execute_result(scalar_one_or_none=existing)
        payload = IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id)

        response = await issue_questionnaire(self.study_id, payload, self.researcher, self.session)

        self.assertIsNone(existing.revoked_at)
        self.assertGreater(existing.issued_at, old_issued_at)
        self.assertEqual(existing.id, response.id)
        self.session.add.assert_not_called()  # reactivated, not re-inserted
        self.session.commit.assert_awaited_once()

    async def test_missing_questionnaire_returns_404(self):
        self.session.get.return_value = None
        payload = IssueQuestionnaireRequest(questionnaire_id=uuid4())

        with self.assertRaises(HTTPException) as raised:
            await issue_questionnaire(self.study_id, payload, self.researcher, self.session)

        self.assertEqual(404, raised.exception.status_code)
        self.session.execute.assert_not_awaited()
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_concurrent_issue_race_reactivates_winning_row_instead_of_erroring(self):
        # Two requests both see "no existing row" (first execute call
        # returns None), both try to insert. This one's commit loses the
        # UniqueConstraint race and raises IntegrityError; the code should
        # roll back, re-query (second execute call, now finding the
        # winner's row), and reactivate it -- not surface a 409.
        winning_row = StudyQuestionnaire(
            id=uuid4(),
            study_id=self.study_id,
            questionnaire_id=self.questionnaire.id,
            issued_at=datetime.now(timezone.utc) - timedelta(seconds=1),
            revoked_at=None,
        )
        self.session.get.return_value = self.questionnaire
        self.session.execute.side_effect = [
            make_execute_result(scalar_one_or_none=None),       # initial check: nothing yet
            make_execute_result(scalar_one_or_none=winning_row),  # re-query after the race
        ]
        self.session.commit = AsyncMock(side_effect=[IntegrityError("insert", {}, Exception("dup")), None])
        self.session.rollback = AsyncMock()
        payload = IssueQuestionnaireRequest(questionnaire_id=self.questionnaire.id)

        response = await issue_questionnaire(self.study_id, payload, self.researcher, self.session)

        self.session.rollback.assert_awaited_once()
        self.assertEqual(winning_row.id, response.id)
        self.assertIsNone(winning_row.revoked_at)
        self.assertEqual(2, self.session.execute.await_count)
        self.assertEqual(2, self.session.commit.await_count)


class ListIssuedQuestionnairesTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.execute = AsyncMock()
        self.study_id = uuid4()
        self.user = User(
            id=uuid4(), email="participant@example.com", password_hash="hash", role=Role.PARTICIPANT
        )

    async def test_returns_issued_questionnaires(self):
        issuances = [
            StudyQuestionnaire(
                id=uuid4(), study_id=self.study_id, questionnaire_id=uuid4(),
                issued_at=datetime.now(timezone.utc), revoked_at=None,
            ),
            StudyQuestionnaire(
                id=uuid4(), study_id=self.study_id, questionnaire_id=uuid4(),
                issued_at=datetime.now(timezone.utc), revoked_at=None,
            ),
        ]
        self.session.execute.return_value = make_execute_result(scalars_all=issuances)

        response = await list_issued_questionnaires(self.study_id, self.user, self.session)

        self.assertEqual(2, len(response.issued_questionnaires))

    async def test_returns_empty_list_when_none_issued(self):
        self.session.execute.return_value = make_execute_result(scalars_all=[])

        response = await list_issued_questionnaires(self.study_id, self.user, self.session)

        self.assertEqual(0, len(response.issued_questionnaires))


class RevokeQuestionnaireTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.researcher = User(
            id=uuid4(), email="researcher@example.com", password_hash="hash", role=Role.RESEARCHER
        )
        self.study_id = uuid4()
        self.issuance = StudyQuestionnaire(
            id=uuid4(),
            study_id=self.study_id,
            questionnaire_id=uuid4(),
            issued_at=datetime.now(timezone.utc),
            revoked_at=None,
        )

    async def test_revokes_active_issuance(self):
        self.session.get.return_value = self.issuance

        response = await revoke_questionnaire(
            self.study_id, self.issuance.id, self.researcher, self.session
        )

        self.assertIsNotNone(self.issuance.revoked_at)
        self.assertIsNotNone(response.revoked_at)
        self.session.commit.assert_awaited_once()

    async def test_missing_issuance_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await revoke_questionnaire(self.study_id, uuid4(), self.researcher, self.session)

        self.assertEqual(404, raised.exception.status_code)
        self.session.commit.assert_not_awaited()

    async def test_issuance_belonging_to_different_study_returns_404(self):
        # A real, existing issuance -- but for a different study than the
        # one named in the URL. _get_issuance must catch this, not just
        # check "does a row with this id exist at all".
        mismatched = StudyQuestionnaire(
            id=self.issuance.id, study_id=uuid4(), questionnaire_id=uuid4(), revoked_at=None
        )
        self.session.get.return_value = mismatched

        with self.assertRaises(HTTPException) as raised:
            await revoke_questionnaire(self.study_id, self.issuance.id, self.researcher, self.session)

        self.assertEqual(404, raised.exception.status_code)

    async def test_already_revoked_returns_409(self):
        self.issuance.revoked_at = datetime.now(timezone.utc)
        self.session.get.return_value = self.issuance

        with self.assertRaises(HTTPException) as raised:
            await revoke_questionnaire(self.study_id, self.issuance.id, self.researcher, self.session)

        self.assertEqual(409, raised.exception.status_code)
        self.session.commit.assert_not_awaited()


class SubmitResponseTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.commit = AsyncMock()
        self.participant = User(
            id=uuid4(), email="participant@example.com", password_hash="hash", role=Role.PARTICIPANT
        )
        self.study_id = uuid4()
        self.study = Study(
            id=self.study_id, name="Morning walks", duration=14, status=StudyStatus.RECRUITING
        )
        self.questionnaire = Questionnaire(
            id=uuid4(),
            title="Weekly check-in",
            status=QuestionnaireStatus.PUBLISHED,
            definition=MINIMAL_DEFINITION,
        )
        self.issuance = StudyQuestionnaire(
            id=uuid4(),
            study_id=self.study_id,
            questionnaire_id=self.questionnaire.id,
            revoked_at=None,
        )

    async def test_submits_valid_response(self):
        self.session.get.side_effect = [self.issuance, self.study, self.questionnaire]
        payload = SubmitAnswersRequest(answers={"q1": "Doing well, thanks"})

        response = await submit_response(
            self.study_id, self.issuance.id, payload, self.participant, self.session
        )

        added = self.session.add.call_args.args[0]
        self.assertIsInstance(added, QuestionnaireResponse)
        self.assertEqual(self.issuance.id, added.study_questionnaire_id)
        self.assertEqual(self.participant.id, added.respondent_id)
        self.assertEqual({"q1": "Doing well, thanks"}, added.answers)
        self.assertEqual(added.id, response.id)
        self.session.commit.assert_awaited_once()

    async def test_rejects_response_to_revoked_issuance(self):
        self.issuance.revoked_at = datetime.now(timezone.utc)
        self.session.get.return_value = self.issuance
        payload = SubmitAnswersRequest(answers={"q1": "Doing well"})

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study_id, self.issuance.id, payload, self.participant, self.session
            )

        self.assertEqual(409, raised.exception.status_code)
        self.session.add.assert_not_called()

    async def test_rejects_response_when_study_not_recruiting_or_active(self):
        self.study.status = StudyStatus.DRAFT
        self.session.get.side_effect = [self.issuance, self.study]
        payload = SubmitAnswersRequest(answers={"q1": "Doing well"})

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study_id, self.issuance.id, payload, self.participant, self.session
            )

        self.assertEqual(409, raised.exception.status_code)
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_rejects_malformed_answers(self):
        # q1 is a text question; a non-string answer should fail
        # validate_response's type check with a 422, not a 500.
        self.session.get.side_effect = [self.issuance, self.study, self.questionnaire]
        payload = SubmitAnswersRequest(answers={"q1": 12345})

        with self.assertRaises(HTTPException) as raised:
            await submit_response(
                self.study_id, self.issuance.id, payload, self.participant, self.session
            )

        self.assertEqual(422, raised.exception.status_code)
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_missing_issuance_returns_404(self):
        self.session.get.return_value = None
        payload = SubmitAnswersRequest(answers={"q1": "Doing well"})

        with self.assertRaises(HTTPException) as raised:
            await submit_response(self.study_id, uuid4(), payload, self.participant, self.session)

        self.assertEqual(404, raised.exception.status_code)


class GetResponsesTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.researcher = User(
            id=uuid4(), email="researcher@example.com", password_hash="hash", role=Role.RESEARCHER
        )
        self.study_id = uuid4()
        self.issuance = StudyQuestionnaire(
            id=uuid4(), study_id=self.study_id, questionnaire_id=uuid4(), revoked_at=None
        )

    async def test_returns_responses_for_issuance(self):
        self.session.get.return_value = self.issuance
        responses = [
            QuestionnaireResponse(
                id=uuid4(),
                study_questionnaire_id=self.issuance.id,
                respondent_id=uuid4(),
                answers={"q1": "Good"},
                submitted_on=datetime.now(timezone.utc),
            ),
            QuestionnaireResponse(
                id=uuid4(),
                study_questionnaire_id=self.issuance.id,
                respondent_id=uuid4(),
                answers={"q1": "Not great"},
                submitted_on=datetime.now(timezone.utc),
            ),
        ]
        self.session.execute.return_value = make_execute_result(scalars_all=responses)

        response = await get_responses(self.study_id, self.issuance.id, self.researcher, self.session)

        self.assertEqual(2, len(response.responses))

    async def test_returns_empty_list_when_no_responses_yet(self):
        self.session.get.return_value = self.issuance
        self.session.execute.return_value = make_execute_result(scalars_all=[])

        response = await get_responses(self.study_id, self.issuance.id, self.researcher, self.session)

        self.assertEqual(0, len(response.responses))

    async def test_missing_issuance_returns_404(self):
        self.session.get.return_value = None

        with self.assertRaises(HTTPException) as raised:
            await get_responses(self.study_id, uuid4(), self.researcher, self.session)

        self.assertEqual(404, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
