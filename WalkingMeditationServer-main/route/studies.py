from uuid import uuid4

from fastapi import APIRouter, status

from route.dependencies import VerifiedResearcherDependency
from route.models import CreateStudyRequest, StudyResponse
from route.persistence.database import DatabaseSessionDependency
from route.persistence.orm_models import Study


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
    session.add(study)
    await session.commit()

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
    )
