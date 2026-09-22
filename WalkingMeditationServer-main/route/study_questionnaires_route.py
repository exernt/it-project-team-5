"""
route/study_questionnaires.py

Two different addressing schemes are used deliberately here:

- Issuing a questionnaire (POST /studies/{study_id}/questionnaires) takes
  a questionnaire_id in the body -- at that point, no StudyQuestionnaire
  row necessarily exists yet, so there's nothing else to address by.

- Every action on an already-issued questionnaire (revoke, submitting a
  response, viewing responses) addresses it by {study_questionnaire_id}
  in the path -- the StudyQuestionnaire row's own UUID, handed back to
  the client by the issue/list endpoints. This is the same pattern every
  other resource in the app uses (Study.id, User.id, Questionnaire.id are
  all addressed directly), and it means these lookups are a plain
  primary-key fetch (session.get) rather than a composite query.

{study_id} still appears in every path for consistency with the rest of
the app's URL hierarchy, and doubles as a free integrity check: each
lookup below confirms the fetched row's own study_id actually matches
the URL's study_id, and 404s if not -- guarding against a
study_questionnaire_id that's real but belongs to a different study than
the one named in the URL.
"""

from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel
from sqlalchemy import select

from route.dependencies import CurrentUserDependency, StudyOwnerDependency
from database.core import DatabaseSessionDependency
from database.models import Questionnaire, QuestionnaireResponse, StudyQuestionnaire

# Adjust to wherever validate_response ends up living.
from questionnaire import validate_response


router = APIRouter(prefix="/studies/{study_id}/questionnaires", tags=["study-questionnaires"])


# ---------------------------------------------------------------------------
# Schemas
# ---------------------------------------------------------------------------

class IssueQuestionnaireRequest(BaseModel):
    questionnaire_id: UUID


class StudyQuestionnaireOut(BaseModel):
    id: UUID  # the study_questionnaire_id clients should hold onto and
              # use for every subsequent call against this issuance
    study_id: UUID
    questionnaire_id: UUID
    issued_at: datetime
    revoked_at: datetime | None


class SubmitAnswersRequest(BaseModel):
    answers: dict[str, Any]


class QuestionnaireAnswerOut(BaseModel):
    id: UUID
    study_questionnaire_id: UUID
    respondent_id: UUID | None
    answers: dict[str, Any]
    submitted_on: datetime


# ---------------------------------------------------------------------------
# Shared lookup
# ---------------------------------------------------------------------------

async def _get_issuance(
    session: DatabaseSessionDependency, study_id: UUID, study_questionnaire_id: UUID
) -> StudyQuestionnaire:
    """Fetch an issuance by its own primary key, and confirm it actually
    belongs to the study named in the URL. Used by every endpoint that
    acts on an already-issued questionnaire (revoke, submit, list)."""
    issuance = await session.get(StudyQuestionnaire, study_questionnaire_id)
    if issuance is None or issuance.study_id != study_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No such questionnaire issuance for this study",
        )
    return issuance


def _to_out(issuance: StudyQuestionnaire) -> StudyQuestionnaireOut:
    return StudyQuestionnaireOut(
        id=issuance.id,
        study_id=issuance.study_id,
        questionnaire_id=issuance.questionnaire_id,
        issued_at=issuance.issued_at,
        revoked_at=issuance.revoked_at,
    )


# ---------------------------------------------------------------------------
# Issuance
# ---------------------------------------------------------------------------

@router.post("", response_model=StudyQuestionnaireOut, status_code=status.HTTP_201_CREATED)
async def issue_questionnaire(
    study_id: UUID,
    payload: IssueQuestionnaireRequest,
    study: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireOut:
    questionnaire = await session.get(Questionnaire, payload.questionnaire_id)
    if questionnaire is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Questionnaire not found",
        )

    # Only (study_id, questionnaire_id) identifies "the same issuance" at
    # this point -- study_questionnaire_id doesn't exist yet if this pair
    # has never been issued before. This is the one place a composite
    # query is still unavoidable.
    existing = (
        await session.execute(
            select(StudyQuestionnaire).where(
                StudyQuestionnaire.study_id == study_id,
                StudyQuestionnaire.questionnaire_id == payload.questionnaire_id,
            )
        )
    ).scalar_one_or_none()

    if existing is not None:
        # Reactivate (Option (a)): same row, same id, so every existing
        # response and every URL a client already has for this issuance
        # stays valid.
        existing.revoked_at = None
        existing.issued_at = datetime.now(timezone.utc)
        await session.commit()
        issuance = existing
    else:
        issuance = StudyQuestionnaire(
            id=uuid4(),
            study_id=study_id,
            questionnaire_id=payload.questionnaire_id,
        )
        session.add(issuance)
        await session.commit()

    return _to_out(issuance)


@router.get("", response_model=list[StudyQuestionnaireOut])
async def list_issued_questionnaires(
    study_id: UUID,
    _user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> list[StudyQuestionnaireOut]:
    result = await session.execute(
        select(StudyQuestionnaire).where(StudyQuestionnaire.study_id == study_id)
    )
    return [_to_out(i) for i in result.scalars().all()]


@router.post("/{study_questionnaire_id}/revoke", response_model=StudyQuestionnaireOut)
async def revoke_questionnaire(
    study_id: UUID,
    study_questionnaire_id: UUID,
    study: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireOut:
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)

    if issuance.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire is already revoked for this study",
        )

    issuance.revoked_at = datetime.now(timezone.utc)
    await session.commit()

    return _to_out(issuance)


# ---------------------------------------------------------------------------
# Responses
# ---------------------------------------------------------------------------

@router.post(
    "/{study_questionnaire_id}/responses",
    response_model=QuestionnaireAnswerOut,
    status_code=status.HTTP_201_CREATED,
)
async def submit_response(
    study_id: UUID,
    study_questionnaire_id: UUID,
    payload: SubmitAnswersRequest,
    user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> QuestionnaireAnswerOut:
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)

    if issuance.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire is no longer active for this study",
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

    return QuestionnaireAnswerOut(
        id=response.id,
        study_questionnaire_id=response.study_questionnaire_id,
        respondent_id=response.respondent_id,
        answers=response.answers,
        submitted_on=response.submitted_on,
    )


@router.get(
    "/{study_questionnaire_id}/responses",
    response_model=list[QuestionnaireAnswerOut],
)
async def get_responses(
    study_id: UUID,
    study_questionnaire_id: UUID,
    study: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> list[QuestionnaireAnswerOut]:
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)

    result = await session.execute(
        select(QuestionnaireResponse).where(
            QuestionnaireResponse.study_questionnaire_id == issuance.id
        )
    )
    responses = result.scalars().all()

    return [
        QuestionnaireAnswerOut(
            id=r.id,
            study_questionnaire_id=r.study_questionnaire_id,
            respondent_id=r.respondent_id,
            answers=r.answers,
            submitted_on=r.submitted_on,
        )
        for r in responses
    ]
