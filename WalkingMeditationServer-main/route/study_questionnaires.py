"""
route/study_questionnaires.py

Schemas now come from route.models, matching every other router's
convention (CreateStudyRequest/StudyResponse, JoinStudyRequest/
StudyRegistrationResponse, etc.) rather than being defined inline here.

See route.models for the naming note on why these are
QuestionnaireAnswerResponse/-ListResponse rather than reusing
"QuestionnaireResponse" -- that name already belongs to a SQLAlchemy
model this file also imports.

submit_response now also checks the study's own status (mirroring how
join_study gates on StudyStatus) -- a response shouldn't be accepted for
a study that's still draft or has already closed, the same way joining
isn't allowed in those states.
"""

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select

from route.dependencies import CurrentUserDependency, StudyOwnerDependency
from route.models import (
    IssueQuestionnaireRequest,
    QuestionnaireAnswerListResponse,
    QuestionnaireAnswerResponse,
    StudyQuestionnaireListResponse,
    StudyQuestionnaireResponse,
    SubmitAnswersRequest,
)
from database.core import DatabaseSessionDependency
from database.models import (
    Questionnaire,
    QuestionnaireResponse,
    Study,
    StudyQuestionnaire,
    StudyStatus,
)

# Adjust to wherever validate_response ends up living.
from questionnaire.questionnaire import validate_response


router = APIRouter(prefix="/studies/{study_id}/questionnaires", tags=["study-questionnaires"])


# ---------------------------------------------------------------------------
# Shared lookup / conversion helpers
# ---------------------------------------------------------------------------

async def _get_issuance(
    session: DatabaseSessionDependency, study_id: UUID, study_questionnaire_id: UUID
) -> StudyQuestionnaire:
    """Fetch an issuance by its own primary key, and confirm it actually
    belongs to the study named in the URL."""
    issuance = await session.get(StudyQuestionnaire, study_questionnaire_id)
    if issuance is None or issuance.study_id != study_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No such questionnaire issuance for this study",
        )
    return issuance


def _to_response(issuance: StudyQuestionnaire) -> StudyQuestionnaireResponse:
    return StudyQuestionnaireResponse(
        id=issuance.id,
        study_id=issuance.study_id,
        questionnaire_id=issuance.questionnaire_id,
        issued_at=issuance.issued_at,
        revoked_at=issuance.revoked_at,
    )


# ---------------------------------------------------------------------------
# Issuance
# ---------------------------------------------------------------------------

@router.post("", response_model=StudyQuestionnaireResponse, status_code=status.HTTP_201_CREATED)
async def issue_questionnaire(
    study_id: UUID,
    payload: IssueQuestionnaireRequest,
    study: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireResponse:
    questionnaire = await session.get(Questionnaire, payload.questionnaire_id)
    if questionnaire is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Questionnaire not found",
        )

    existing = (
        await session.execute(
            select(StudyQuestionnaire).where(
                StudyQuestionnaire.study_id == study_id,
                StudyQuestionnaire.questionnaire_id == payload.questionnaire_id,
            )
        )
    ).scalar_one_or_none()

    if existing is not None:
        existing.revoked_at = None
        existing.issued_at = datetime.now(timezone.utc)
        await session.commit()
        issuance = existing
    else:
        issuance = StudyQuestionnaire(
            id=uuid4(),
            study_id=study_id,
            questionnaire_id=payload.questionnaire_id,
            issued_at=datetime.now(timezone.utc),
        )
        session.add(issuance)
        await session.commit()

    return _to_response(issuance)


@router.get("", response_model=StudyQuestionnaireListResponse)
async def list_issued_questionnaires(
    study_id: UUID,
    _user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireListResponse:
    result = await session.execute(
        select(StudyQuestionnaire).where(StudyQuestionnaire.study_id == study_id)
    )
    issuances = result.scalars().all()

    return StudyQuestionnaireListResponse(
        issued_questionnaires=[_to_response(i) for i in issuances]
    )


@router.post("/{study_questionnaire_id}/revoke", response_model=StudyQuestionnaireResponse)
async def revoke_questionnaire(
    study_id: UUID,
    study_questionnaire_id: UUID,
    study: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireResponse:
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)

    if issuance.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire is already revoked for this study",
        )

    issuance.revoked_at = datetime.now(timezone.utc)
    await session.commit()

    return _to_response(issuance)


# ---------------------------------------------------------------------------
# Responses
# ---------------------------------------------------------------------------

@router.post(
    "/{study_questionnaire_id}/responses",
    response_model=QuestionnaireAnswerResponse,
    status_code=status.HTTP_201_CREATED,
)
async def submit_response(
    study_id: UUID,
    study_questionnaire_id: UUID,
    payload: SubmitAnswersRequest,
    user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireAnswerResponse:
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)

    if issuance.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire is no longer active for this study",
        )

    # Mirrors join_study's status gate: a study only accepts activity
    # while recruiting or active, not draft or closed.
    study_row = await session.get(Study, study_id)
    if study_row.status not in (StudyStatus.RECRUITING, StudyStatus.ACTIVE):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This study is not currently accepting questionnaire responses",
        )

    questionnaire = await session.get(Questionnaire, issuance.questionnaire_id)
    try:
        validate_response(questionnaire.definition, payload.answers)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=str(e),
        ) from e

    response = QuestionnaireResponse(
        id=uuid4(),
        study_questionnaire_id=issuance.id,
        respondent_id=user.id,
        answers=payload.answers,
        submitted_on=datetime.now(timezone.utc),
    )
    session.add(response)
    await session.commit()

    return QuestionnaireAnswerResponse(
        id=response.id,
        study_questionnaire_id=response.study_questionnaire_id,
        respondent_id=response.respondent_id,
        answers=response.answers,
        submitted_on=response.submitted_on,
    )


@router.get(
    "/{study_questionnaire_id}/responses",
    response_model=QuestionnaireAnswerListResponse,
)
async def get_responses(
    study_id: UUID,
    study_questionnaire_id: UUID,
    study: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireAnswerListResponse:
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)

    result = await session.execute(
        select(QuestionnaireResponse).where(
            QuestionnaireResponse.study_questionnaire_id == issuance.id
        )
    )
    responses = result.scalars().all()

    return QuestionnaireAnswerListResponse(
        responses=[
            QuestionnaireAnswerResponse(
                id=r.id,
                study_questionnaire_id=r.study_questionnaire_id,
                respondent_id=r.respondent_id,
                answers=r.answers,
                submitted_on=r.submitted_on,
            )
            for r in responses
        ]
    )