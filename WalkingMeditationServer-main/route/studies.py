from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy import select

from route.dependencies import CurrentUserDependency, StudyOwnerDependency, VerifiedResearcherDependency
from route.models import (
    ConsentFormResponse,
    CreateStudyRequest,
    StudyListResponse,
    StudyRegistrationResponse,
    StudyResponse,
)
from database.core import DatabaseSessionDependency
from database.models import ConsentForm, Study, StudyCreatedBy, StudyRegistration, StudyStatus

router = APIRouter(prefix="/studies", tags=["studies"])


@router.post(
    "",
    response_model=StudyResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_study(
        payload: CreateStudyRequest,
        _researcher: VerifiedResearcherDependency,
        session: DatabaseSessionDependency,
) -> StudyResponse:
    study = Study(
        id=uuid4(),
        name=payload.name,
        duration=payload.duration,
        status=StudyStatus.DRAFT,
    )
    ownership = StudyCreatedBy(
        researcher_id=_researcher.id,
        study_id=study.id,
    )
    consent_form = ConsentForm(
        study_id=study.id,
        body_text="Placeholder consent text — to be finalized.",
        version=1,
    )
    session.add_all([study, ownership, consent_form])
    await session.commit()

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        status=study.status.value,
    )
@router.post(
    "/{study_id}/join",
    response_model=StudyRegistrationResponse,
    status_code=status.HTTP_201_CREATED,
)
async def join_study(
        study_id: UUID,
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> StudyRegistrationResponse:
    study = await session.get(Study, study_id)
    if study is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Study not found",
        )

    if study.status not in (StudyStatus.RECRUITING, StudyStatus.ACTIVE):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This study is not currently accepting participants",
        )

    existing_registration = await session.get(
        StudyRegistration,
        (user.id, study_id),
    )
    if existing_registration is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="User is already registered for this study",
        )

    registered_at = datetime.now(timezone.utc)
    registration = StudyRegistration(
        user_id=user.id,
        study_id=study_id,
        registered_at=registered_at,
    )
    session.add(registration)

    try:
        await session.commit()
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="User is already registered for this study",
        ) from exc

    return StudyRegistrationResponse(
        user_id=registration.user_id,
        study_id=registration.study_id,
        registered_at=registration.registered_at,
    )

@router.get(
    "",
    response_model=StudyListResponse,
)
async def list_studies(
        _user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> StudyListResponse:
    studies = await session.scalars(select(Study))
    return StudyListResponse(
        studies=[
            StudyResponse(id=s.id, name=s.name, duration=s.duration, status=s.status.value)
            for s in studies
        ]
    )


@router.get(
    "/{study_id}",
    response_model=StudyResponse,
)
async def get_study(
        study_id: UUID,
        _user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> StudyResponse:
    study = await session.get(Study, study_id)
    if study is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Study not found",
        )

    return StudyResponse(id=study.id, name=study.name, duration=study.duration, status=study.status.value)

def _invalid_transition(current: StudyStatus, target: StudyStatus) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail=f"Cannot move study from '{current.value}' to '{target.value}'",
    )


@router.post(
    "/{study_id}/publish",
    response_model=StudyResponse,
)
async def publish_study(
        study_id: UUID,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> StudyResponse:
    study = await session.get(Study, study_id)

    if study.status != StudyStatus.DRAFT:
        raise _invalid_transition(study.status, StudyStatus.RECRUITING)

    study.status = StudyStatus.RECRUITING
    await session.commit()

    return StudyResponse(id=study.id, name=study.name, duration=study.duration, status=study.status.value)


@router.post(
    "/{study_id}/activate",
    response_model=StudyResponse,
)
async def activate_study(
        study_id: UUID,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> StudyResponse:
    study = await session.get(Study, study_id)

    if study.status != StudyStatus.RECRUITING:
        raise _invalid_transition(study.status, StudyStatus.ACTIVE)

    study.status = StudyStatus.ACTIVE
    await session.commit()

    return StudyResponse(id=study.id, name=study.name, duration=study.duration, status=study.status.value)


@router.post(
    "/{study_id}/close",
    response_model=StudyResponse,
)
async def close_study(
        study_id: UUID,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> StudyResponse:
    study = await session.get(Study, study_id)

    if study.status not in (StudyStatus.RECRUITING, StudyStatus.ACTIVE):
        raise _invalid_transition(study.status, StudyStatus.CLOSED)

    study.status = StudyStatus.CLOSED
    await session.commit()

    return StudyResponse(id=study.id, name=study.name, duration=study.duration, status=study.status.value)

@router.get(
    "/{study_id}/consent-form",
    response_model=ConsentFormResponse,
)
async def get_consent_form(
        study_id: UUID,
        _user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> ConsentFormResponse:
    consent_form = await session.get(ConsentForm, study_id)
    if consent_form is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Consent form not found",
        )

    return ConsentFormResponse(
        study_id=consent_form.study_id,
        body_text=consent_form.body_text,
        version=consent_form.version,
    )