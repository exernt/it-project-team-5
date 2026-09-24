"""Study questionnaire issuance rounds and participant responses."""

from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import and_, func, select
from sqlalchemy.exc import IntegrityError

from route.dependencies import CurrentUserDependency, StudyOwnerDependency
from route.models import (
    IssueQuestionnaireRequest,
    QuestionnaireAnswerListResponse,
    QuestionnaireAnswerResponse,
    StudyQuestionnaireDetailResponse,
    StudyQuestionnaireListResponse,
    StudyQuestionnaireResponse,
    SubmitAnswersRequest,
)
from database.core import DatabaseSessionDependency
from database.models import (
    Questionnaire,
    QuestionnaireResponse,
    QuestionnaireStatus,
    Study,
    StudyCreatedBy,
    StudyQuestionnaire,
    StudyRegistration,
    StudyStatus,
)

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


def _to_response(
    issuance: StudyQuestionnaire,
    questionnaire: Questionnaire,
    has_responded: bool | None = None,
) -> StudyQuestionnaireResponse:
    return StudyQuestionnaireResponse(
        id=issuance.id,
        study_id=issuance.study_id,
        questionnaire_id=issuance.questionnaire_id,
        round_number=issuance.round_number,
        title=questionnaire.title,
        issued_at=issuance.issued_at,
        revoked_at=issuance.revoked_at,
        has_responded=has_responded,
    )


async def _require_study_access(
    session: DatabaseSessionDependency,
    study_id: UUID,
    user_id: UUID,
) -> str:
    """Return owner/participant access, rejecting unrelated users."""
    registration = await session.get(StudyRegistration, (user_id, study_id))
    if registration is not None and registration.withdrawn_at is None:
        return "participant"

    ownership = await session.get(StudyCreatedBy, study_id)
    if ownership is not None and ownership.researcher_id == user_id:
        return "owner"

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="You are not an active participant in or owner of this study",
    )


# ---------------------------------------------------------------------------
# Issuance
# ---------------------------------------------------------------------------

@router.post("", response_model=StudyQuestionnaireResponse, status_code=status.HTTP_201_CREATED)
async def issue_questionnaire(
    study_id: UUID,
    payload: IssueQuestionnaireRequest,
    owner: StudyOwnerDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireResponse:
    questionnaire = await session.get(Questionnaire, payload.questionnaire_id)
    if questionnaire is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Questionnaire not found",
        )
    if questionnaire.researcher_id != owner.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You do not own this questionnaire",
        )
    if questionnaire.status != QuestionnaireStatus.PUBLISHED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only published questionnaires can be issued",
        )

    study_row = await session.get(Study, study_id)
    if study_row.status not in (StudyStatus.RECRUITING, StudyStatus.ACTIVE):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Questionnaires can only be issued to recruiting or active studies",
        )

    # Lock the study while allocating the next round number. This serializes
    # simultaneous issuance requests for this study and prevents both from
    # choosing the same (study, questionnaire, round) natural key.
    await session.execute(
        select(Study.id).where(Study.id == study_id).with_for_update()
    )
    current_round = await session.scalar(
        select(func.max(StudyQuestionnaire.round_number)).where(
            StudyQuestionnaire.study_id == study_id,
            StudyQuestionnaire.questionnaire_id == payload.questionnaire_id,
        )
    )
    issuance = StudyQuestionnaire(
        id=uuid4(),
        study_id=study_id,
        questionnaire_id=payload.questionnaire_id,
        round_number=(current_round or 0) + 1,
        issued_at=datetime.now(timezone.utc),
    )
    session.add(issuance)
    await session.commit()

    return _to_response(issuance, questionnaire)


@router.get("", response_model=StudyQuestionnaireListResponse)
async def list_issued_questionnaires(
    study_id: UUID,
    user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireListResponse:
    access = await _require_study_access(session, study_id, user.id)
    conditions = [StudyQuestionnaire.study_id == study_id]
    if access == "participant":
        conditions.extend(
            [
                StudyQuestionnaire.revoked_at.is_(None),
                Questionnaire.status == QuestionnaireStatus.PUBLISHED,
                Study.status.in_((StudyStatus.RECRUITING, StudyStatus.ACTIVE)),
            ]
        )

    result = await session.execute(
        select(StudyQuestionnaire, Questionnaire, QuestionnaireResponse.id)
        .join(Questionnaire, Questionnaire.id == StudyQuestionnaire.questionnaire_id)
        .join(Study, Study.id == StudyQuestionnaire.study_id)
        .outerjoin(
            QuestionnaireResponse,
            and_(
                QuestionnaireResponse.study_questionnaire_id == StudyQuestionnaire.id,
                QuestionnaireResponse.respondent_id == user.id,
            ),
        )
        .where(*conditions)
        .order_by(StudyQuestionnaire.issued_at.desc())
    )

    return StudyQuestionnaireListResponse(
        issued_questionnaires=[
            _to_response(issuance, questionnaire, response_id is not None)
            for issuance, questionnaire, response_id in result.all()
        ]
    )


@router.get(
    "/{study_questionnaire_id}",
    response_model=StudyQuestionnaireDetailResponse,
)
async def get_issued_questionnaire(
    study_id: UUID,
    study_questionnaire_id: UUID,
    user: CurrentUserDependency,
    session: DatabaseSessionDependency,
) -> StudyQuestionnaireDetailResponse:
    access = await _require_study_access(session, study_id, user.id)
    issuance = await _get_issuance(session, study_id, study_questionnaire_id)
    questionnaire = await session.get(Questionnaire, issuance.questionnaire_id)
    study_row = await session.get(Study, study_id)
    if access == "participant" and (
        issuance.revoked_at is not None
        or questionnaire.status != QuestionnaireStatus.PUBLISHED
        or study_row.status not in (StudyStatus.RECRUITING, StudyStatus.ACTIVE)
    ):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No open questionnaire round with this id",
        )

    response_id = await session.scalar(
        select(QuestionnaireResponse.id).where(
            QuestionnaireResponse.study_questionnaire_id == issuance.id,
            QuestionnaireResponse.respondent_id == user.id,
        )
    )
    summary = _to_response(issuance, questionnaire, response_id is not None)
    return StudyQuestionnaireDetailResponse(
        **summary.model_dump(),
        definition=questionnaire.definition,
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

    questionnaire = await session.get(Questionnaire, issuance.questionnaire_id)
    return _to_response(issuance, questionnaire)


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

    registration = await session.get(StudyRegistration, (user.id, study_id))
    if registration is None or registration.withdrawn_at is not None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only active participants in this study can submit questionnaire responses",
        )

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
    if questionnaire.status != QuestionnaireStatus.PUBLISHED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire is not published",
        )

    existing_response_id = await session.scalar(
        select(QuestionnaireResponse.id).where(
            QuestionnaireResponse.study_questionnaire_id == issuance.id,
            QuestionnaireResponse.respondent_id == user.id,
        )
    )
    if existing_response_id is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You have already responded to this questionnaire round",
        )

    try:
        validate_response(questionnaire.definition, payload.answers)
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
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
    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You have already responded to this questionnaire round",
        ) from exc

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
