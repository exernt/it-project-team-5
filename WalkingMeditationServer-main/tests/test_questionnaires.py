import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock
from uuid import uuid4

from fastapi import HTTPException

from database.models import Questionnaire, QuestionnaireStatus, Role, User
from route.models import CreateQuestionnaireRequest
from route.questionnaires import (
    create_questionnaire,
    get_questionnaire,
    list_questionnaires,
    publish_questionnaire,
    replace_questionnaire,
)


DEFINITION = {
    "title": " Daily check-in ",
    "questions": [
        {
            "id": "mood",
            "type": "rating",
            "prompt": "How is your mood?",
            "required": True,
            "scale_ref": "mood",
        }
    ],
    "scales": {
        "mood": {
            "min": 1,
            "max": 5,
        }
    },
}


class QuestionnaireDefinitionEndpointTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.get = AsyncMock()
        self.session.execute = AsyncMock()
        self.session.commit = AsyncMock()
        self.session.refresh = AsyncMock()
        self.researcher = User(
            id=uuid4(),
            email="researcher@example.com",
            password_hash="hash",
            role=Role.RESEARCHER,
        )

    async def test_create_stores_valid_definition_as_draft(self):
        response = await create_questionnaire(
            CreateQuestionnaireRequest(definition=DEFINITION),
            self.researcher,
            self.session,
        )

        added = self.session.add.call_args.args[0]
        self.assertEqual(QuestionnaireStatus.DRAFT, added.status)
        self.assertEqual("Daily check-in", added.title)
        self.assertEqual("Daily check-in", added.definition["title"])
        self.assertEqual("draft", response.status)

    async def test_create_rejects_invalid_definition(self):
        with self.assertRaises(HTTPException) as raised:
            await create_questionnaire(
                CreateQuestionnaireRequest(
                    definition={"title": "Bad", "questions": []}
                ),
                self.researcher,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)
        self.session.add.assert_not_called()

    async def test_list_is_scoped_to_current_researcher(self):
        questionnaire = Questionnaire(
            id=uuid4(),
            title="Mine",
            researcher_id=self.researcher.id,
            status=QuestionnaireStatus.DRAFT,
            definition={**DEFINITION, "title": "Mine"},
            created_on=datetime.now(timezone.utc),
        )
        result = MagicMock()
        result.scalars.return_value.all.return_value = [questionnaire]
        self.session.execute.return_value = result

        response = await list_questionnaires(self.researcher, self.session)

        self.assertEqual([questionnaire.id], [item.id for item in response.questionnaires])

    async def test_retrieval_hides_another_researchers_questionnaire(self):
        questionnaire = Questionnaire(
            id=uuid4(),
            title="Not mine",
            researcher_id=uuid4(),
            status=QuestionnaireStatus.DRAFT,
            definition={**DEFINITION, "title": "Not mine"},
            created_on=datetime.now(timezone.utc),
        )
        self.session.get.return_value = questionnaire

        with self.assertRaises(HTTPException) as raised:
            await get_questionnaire(questionnaire.id, self.researcher, self.session)

        self.assertEqual(404, raised.exception.status_code)

    async def test_owner_can_publish_valid_questionnaire(self):
        questionnaire = Questionnaire(
            id=uuid4(),
            title="Mine",
            researcher_id=self.researcher.id,
            status=QuestionnaireStatus.DRAFT,
            definition={**DEFINITION, "title": "Mine"},
            created_on=datetime.now(timezone.utc),
        )
        self.session.get.return_value = questionnaire

        response = await publish_questionnaire(
            questionnaire.id, self.researcher, self.session
        )

        self.assertEqual(QuestionnaireStatus.PUBLISHED, questionnaire.status)
        self.assertEqual("published", response.status)

    async def test_owner_can_replace_entire_draft_definition(self):
        questionnaire = Questionnaire(
            id=uuid4(),
            title="Old title",
            researcher_id=self.researcher.id,
            status=QuestionnaireStatus.DRAFT,
            definition={**DEFINITION, "title": "Old title"},
            created_on=datetime.now(timezone.utc),
        )
        self.session.get.return_value = questionnaire
        replacement = {
            "title": " Revised check-in ",
            "questions": [
                {
                    "id": "notes",
                    "type": "text",
                    "prompt": "What changed?",
                    "required": True,
                }
            ],
        }

        response = await replace_questionnaire(
            questionnaire.id,
            CreateQuestionnaireRequest(definition=replacement),
            self.researcher,
            self.session,
        )

        self.assertEqual("Revised check-in", questionnaire.title)
        self.assertEqual("Revised check-in", questionnaire.definition["title"])
        self.assertEqual(replacement["questions"], questionnaire.definition["questions"])
        self.assertEqual(questionnaire.definition, response.definition)
        self.session.commit.assert_awaited_once()
        self.session.refresh.assert_awaited_once_with(questionnaire)

    async def test_published_questionnaire_cannot_be_replaced(self):
        questionnaire = Questionnaire(
            id=uuid4(),
            title="Published",
            researcher_id=self.researcher.id,
            status=QuestionnaireStatus.PUBLISHED,
            definition={**DEFINITION, "title": "Published"},
            created_on=datetime.now(timezone.utc),
        )
        self.session.get.return_value = questionnaire

        with self.assertRaises(HTTPException) as raised:
            await replace_questionnaire(
                questionnaire.id,
                CreateQuestionnaireRequest(definition=DEFINITION),
                self.researcher,
                self.session,
            )

        self.assertEqual(409, raised.exception.status_code)
        self.session.commit.assert_not_awaited()

    async def test_invalid_replacement_leaves_draft_unchanged(self):
        original_definition = {**DEFINITION, "title": "Original"}
        questionnaire = Questionnaire(
            id=uuid4(),
            title="Original",
            researcher_id=self.researcher.id,
            status=QuestionnaireStatus.DRAFT,
            definition=original_definition,
            created_on=datetime.now(timezone.utc),
        )
        self.session.get.return_value = questionnaire

        with self.assertRaises(HTTPException) as raised:
            await replace_questionnaire(
                questionnaire.id,
                CreateQuestionnaireRequest(
                    definition={"title": "Invalid", "questions": []}
                ),
                self.researcher,
                self.session,
            )

        self.assertEqual(422, raised.exception.status_code)
        self.assertEqual("Original", questionnaire.title)
        self.assertEqual(original_definition, questionnaire.definition)
        self.session.commit.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
