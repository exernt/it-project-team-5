import unittest
from unittest.mock import AsyncMock, MagicMock
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import HTTPException
from pydantic import ValidationError

from database.models import (
    Feedback,
    FeedbackCategory,
    Role,
    Study,
    StudyRegistration,
    User,
)
from route.feedback import list_general_feedback, submit_feedback
from route.models import SubmitFeedbackRequest


class SubmitFeedbackTests(unittest.IsolatedAsyncioTestCase):
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

    async def test_submits_general_feedback_without_study(self):
        payload = SubmitFeedbackRequest(category="general", rating=5)

        response = await submit_feedback(payload, self.user, self.session)

        added_feedback = self.session.add.call_args.args[0]
        self.assertIsInstance(added_feedback, Feedback)
        self.assertIsNone(added_feedback.study_id)
        self.assertEqual(self.user.id, response.user_id)
        self.assertEqual(5, response.rating)
        self.session.commit.assert_awaited_once()

    async def test_submits_study_feedback(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14)
        registration = StudyRegistration(
            user_id=self.user.id,
            study_id=study.id,
            consented_at=datetime.now(timezone.utc),
        )
        self.session.get.side_effect = [study, registration]
        payload = SubmitFeedbackRequest(
            study_id=study.id,
            category="study",
            rating=5,
            comment="Really enjoyed this.",
        )

        response = await submit_feedback(payload, self.user, self.session)

        self.assertEqual(study.id, response.study_id)
        self.assertEqual("Really enjoyed this.", response.comment)

    async def test_rejects_feedback_for_study_user_is_not_registered_for(self):
        study = Study(id=uuid4(), name="Morning walks", duration=14)
        self.session.get.side_effect = [study, None]
        payload = SubmitFeedbackRequest(
            study_id=study.id,
            category="study",
            rating=4,
        )

        with self.assertRaises(HTTPException) as raised:
            await submit_feedback(payload, self.user, self.session)

        self.assertEqual(403, raised.exception.status_code)
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    async def test_rejects_feedback_for_missing_study(self):
        self.session.get.return_value = None
        payload = SubmitFeedbackRequest(
            study_id=uuid4(),
            category="study",
            rating=3,
        )

        with self.assertRaises(HTTPException) as raised:
            await submit_feedback(payload, self.user, self.session)

        self.assertEqual(404, raised.exception.status_code)
        self.session.add.assert_not_called()

    def test_rejects_invalid_category(self):
        with self.assertRaises(ValidationError):
            SubmitFeedbackRequest(category="not-a-real-category", rating=3)

    def test_rejects_rating_outside_range(self):
        with self.assertRaises(ValidationError):
            SubmitFeedbackRequest(category="general", rating=6)

    def test_rejects_feedback_without_rating(self):
        with self.assertRaises(ValidationError):
            SubmitFeedbackRequest(category="general")

    def test_rejects_comment_only_feedback(self):
        with self.assertRaises(ValidationError):
            SubmitFeedbackRequest(category="general", comment="Great app")

    def test_accepts_rating_with_optional_comment(self):
        payload = SubmitFeedbackRequest(category="general", rating=4, comment="Nice")
        self.assertEqual(4, payload.rating)

class ListGeneralFeedbackTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalars = AsyncMock()
        self.researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )

    async def test_returns_feedback_without_study(self):
        feedback_item = Feedback(
            id=uuid4(),
            user_id=uuid4(),
            study_id=None,
            category=FeedbackCategory.GENERAL,
            rating=5,
            comment="Loved the app",
            created_at=datetime.now(timezone.utc),
        )
        self.session.scalars.return_value = [feedback_item]

        response = await list_general_feedback(self.researcher, self.session)

        self.assertEqual(1, len(response.feedback))
        self.assertIsNone(response.feedback[0].study_id)
        self.assertEqual(5, response.feedback[0].rating)

    async def test_non_admin_is_rejected_by_route_dependency(self):
        from fastapi import HTTPException
        from route.dependencies import require_role

        check = require_role(Role.ADMIN)
        with self.assertRaises(HTTPException) as ctx:
            await check(self.researcher)
        self.assertEqual(403, ctx.exception.status_code)

    async def test_admin_can_also_list_general_feedback(self):
        admin = User(
            id=uuid4(),
            email="admin@example.com",
            password_hash="hash",
            role=Role.ADMIN,
        )
        self.session.scalars.return_value = []

        response = await list_general_feedback(admin, self.session)

        self.assertEqual([], response.feedback)


if __name__ == "__main__":
    unittest.main()