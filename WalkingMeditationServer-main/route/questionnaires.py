"""Researcher-owned questionnaire definition endpoints."""

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select

from database.core import DatabaseSessionDependency
from database.models import Questionnaire, QuestionnaireStatus
from questionnaire.questionnaire import validate_questionnaire
from route.dependencies import VerifiedResearcherDependency
from route.models import (
    CreateQuestionnaireRequest,
    QuestionnaireDefinitionListResponse,
    QuestionnaireDefinitionResponse,
)


router = APIRouter(prefix="/questionnaires", tags=["questionnaires"])


def _to_response(questionnaire: Questionnaire) -> QuestionnaireDefinitionResponse:
    return QuestionnaireDefinitionResponse(
        id=questionnaire.id,
        title=questionnaire.title,
        researcher_id=questionnaire.researcher_id,
        status=questionnaire.status.value,
        definition=questionnaire.definition,
        created_on=questionnaire.created_on,
    )


async def _get_owned_questionnaire(
    questionnaire_id: UUID,
    researcher: VerifiedResearcherDependency,
    session: DatabaseSessionDependency,
) -> Questionnaire:
    questionnaire = await session.get(Questionnaire, questionnaire_id)
    if questionnaire is None or questionnaire.researcher_id != researcher.id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Questionnaire not found",
        )
    return questionnaire


@router.post(
    "",
    response_model=QuestionnaireDefinitionResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_questionnaire(
    payload: CreateQuestionnaireRequest,
    researcher: VerifiedResearcherDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireDefinitionResponse:
    try:
        validate_questionnaire(payload.definition)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        ) from exc

    definition = dict(payload.definition)
    definition["title"] = str(definition["title"]).strip()
    questionnaire = Questionnaire(
        id=uuid4(),
        title=definition["title"],
        researcher_id=researcher.id,
        status=QuestionnaireStatus.DRAFT,
        definition=definition,
        created_on=datetime.now(timezone.utc),
    )
    session.add(questionnaire)
    await session.commit()
    return _to_response(questionnaire)


@router.get("", response_model=QuestionnaireDefinitionListResponse)
async def list_questionnaires(
    researcher: VerifiedResearcherDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireDefinitionListResponse:
    result = await session.execute(
        select(Questionnaire)
        .where(Questionnaire.researcher_id == researcher.id)
        .order_by(Questionnaire.created_on.desc())
    )
    return QuestionnaireDefinitionListResponse(
        questionnaires=[_to_response(item) for item in result.scalars().all()]
    )


@router.get("/{questionnaire_id}", response_model=QuestionnaireDefinitionResponse)
async def get_questionnaire(
    questionnaire_id: UUID,
    researcher: VerifiedResearcherDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireDefinitionResponse:
    questionnaire = await _get_owned_questionnaire(questionnaire_id, researcher, session)
    return _to_response(questionnaire)


@router.put("/{questionnaire_id}", response_model=QuestionnaireDefinitionResponse)
async def replace_questionnaire(
    questionnaire_id: UUID,
    payload: CreateQuestionnaireRequest,
    researcher: VerifiedResearcherDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireDefinitionResponse:
    questionnaire = await _get_owned_questionnaire(questionnaire_id, researcher, session)
    if questionnaire.status != QuestionnaireStatus.DRAFT:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only draft questionnaires can be edited",
        )

    try:
        validate_questionnaire(payload.definition)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        ) from exc

    definition = dict(payload.definition)
    definition["title"] = str(definition["title"]).strip()
    questionnaire.title = definition["title"]
    questionnaire.definition = definition
    await session.commit()
    await session.refresh(questionnaire)
    return _to_response(questionnaire)


@router.post("/{questionnaire_id}/publish", response_model=QuestionnaireDefinitionResponse)
async def publish_questionnaire(
    questionnaire_id: UUID,
    researcher: VerifiedResearcherDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireDefinitionResponse:
    questionnaire = await _get_owned_questionnaire(questionnaire_id, researcher, session)
    if questionnaire.status == QuestionnaireStatus.ARCHIVED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="An archived questionnaire cannot be published",
        )

    try:
        validate_questionnaire(questionnaire.definition)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        ) from exc

    questionnaire.status = QuestionnaireStatus.PUBLISHED
    await session.commit()
    return _to_response(questionnaire)
