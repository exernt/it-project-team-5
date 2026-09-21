import unittest
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException
from pydantic import ValidationError

from database.models import Feedback, FeedbackCategory, Role, Study, User
from route.feedback import submit_feedback
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
        self.session.get.return_value = study
        payload = SubmitFeedbackRequest(
            study_id=study.id,
            category="study",
            comment="Really enjoyed this.",
        )

        response = await submit_feedback(payload, self.user, self.session)

        self.assertEqual(study.id, response.study_id)
        self.assertEqual("Really enjoyed this.", response.comment)

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

    def test_rejects_feedback_with_neither_rating_nor_comment(self):
        with self.assertRaises(ValidationError):
            SubmitFeedbackRequest(category="general")


if __name__ == "__main__":
    unittest.main()