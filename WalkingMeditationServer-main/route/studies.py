from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, status
from sqlalchemy.exc import IntegrityError

from route.dependencies import CurrentUserDependency, VerifiedResearcherDependency
from route.models import CreateStudyRequest, StudyRegistrationResponse, StudyResponse
from database.core import DatabaseSessionDependency
from database.models import Study, StudyCreatedBy, StudyRegistration


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
    )
    ownership = StudyCreatedBy(
        researcher_id=_researcher.id,
        study_id=study.id,
    )
    session.add_all([study, ownership])
    await session.commit()

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
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
