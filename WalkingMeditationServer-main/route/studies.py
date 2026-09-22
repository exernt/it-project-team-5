from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy.exc import IntegrityError
from sqlalchemy import func, select

from route.dependencies import CurrentUserDependency, StudyOwnerDependency, VerifiedResearcherDependency
from route.models import (
    ConsentFormResponse,
    CreateStudyRequest,
    CurrentStudyScriptParametersResponse,
    EditStudyRequest,
    FeedbackListResponse,
    FeedbackResponse,
    JoinStudyRequest,
    MyStudiesResponse,
    MyStudyResponse,
    StudyGroupAssignmentResponse,
    StudyListResponse,
    StudyParticipantStatusResponse,
    StudyRegistrationResponse,
    StudyResponse,
    StudyStatusResponse,
    SwapStudyGroupRequest,
)
from database.core import DatabaseSessionDependency
from database.models import (
    ConsentForm,
    Feedback,
    Study,
    StudyCreatedBy,
    StudyGroup,
    StudyRegistration,
    StudyStatus,
    User,
)

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
        description="",
        script_generation_parameters="",
        status=StudyStatus.DRAFT,
    )
    session.add(study)
    await session.flush()  # ensures study exists in the DB before FK-dependent inserts

    ownership = StudyCreatedBy(
        researcher_id=_researcher.id,
        study_id=study.id,
    )
    consent_form = ConsentForm(
        study_id=study.id,
        body_text="Placeholder consent text — to be finalized.",
        version=1,
    )
    session.add_all([ownership, consent_form])
    await session.commit()

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        description=study.description or "",
        status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
    )

@router.post(
    "/{study_id}/join",
    response_model=StudyRegistrationResponse,
    status_code=status.HTTP_201_CREATED,
)
async def join_study(
        study_id: UUID,
        payload: JoinStudyRequest,
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

    if not payload.agrees_to_consent:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="You must agree to the consent form to join this study",
        )

    # Serialize joins for this user. Without this row lock, two simultaneous
    # requests for different studies could both pass the check below.
    await session.execute(
        select(User.id)
        .where(User.id == user.id)
        .with_for_update()
    )

    now = datetime.now(timezone.utc)
    existing_registration = await session.get(
        StudyRegistration,
        (user.id, study_id),
    )

    if existing_registration is not None:
        if existing_registration.withdrawn_at is None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="User is already registered for this study",
            )

    current_study_id = await session.scalar(
        select(StudyRegistration.study_id)
        .join(Study, Study.id == StudyRegistration.study_id)
        .where(
            StudyRegistration.user_id == user.id,
            StudyRegistration.study_id != study_id,
            StudyRegistration.withdrawn_at.is_(None),
            Study.status.in_((StudyStatus.RECRUITING, StudyStatus.ACTIVE)),
        )
        .limit(1)
    )
    if current_study_id is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You are already participating in another study",
        )

    if existing_registration is not None:
        # Rejoining after a prior withdrawal — reactivate the existing row
        # rather than inserting a second one (user_id, study_id) is the PK.
        existing_registration.registered_at = now
        existing_registration.consented_at = now
        existing_registration.withdrawn_at = None
        registration = existing_registration
    else:
        # Lock the study while counting and assigning so simultaneous joins
        # cannot both observe the same group sizes.
        await session.execute(
            select(Study.id)
            .where(Study.id == study_id)
            .with_for_update()
        )
        control_count = await session.scalar(
            select(func.count())
            .select_from(StudyRegistration)
            .where(
                StudyRegistration.study_id == study_id,
                StudyRegistration.study_group == StudyGroup.CONTROL,
            )
        )
        experimental_count = await session.scalar(
            select(func.count())
            .select_from(StudyRegistration)
            .where(
                StudyRegistration.study_id == study_id,
                StudyRegistration.study_group == StudyGroup.EXPERIMENTAL,
            )
        )
        study_group = (
            StudyGroup.CONTROL
            if (control_count or 0) <= (experimental_count or 0)
            else StudyGroup.EXPERIMENTAL
        )
        registration = StudyRegistration(
            user_id=user.id,
            study_id=study_id,
            registered_at=now,
            consented_at=now,
            study_group=study_group,
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
        consented_at=registration.consented_at,
        withdrawn_at=registration.withdrawn_at,
    )

@router.get(
    "",
    response_model=StudyListResponse,
)
async def list_studies(
        _user: CurrentUserDependency,
        session: DatabaseSessionDependency,
        status_filter: StudyStatus | None = Query(default=None, alias="status"),
) -> StudyListResponse:
    query = select(Study)
    if status_filter is not None:
        query = query.where(Study.status == status_filter)

    studies = await session.scalars(query)
    return StudyListResponse(
        studies=[
            StudyResponse(
                id=s.id,
                name=s.name,
                duration=s.duration,
                description=s.description or "",
                status=s.status.value,
                script_generation_parameters=s.script_generation_parameters or "",
            )
            for s in studies
        ]
    )

@router.get(
    "/mine",
    response_model=MyStudiesResponse,
)
async def list_my_studies(
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> MyStudiesResponse:
    result = await session.execute(
        select(StudyRegistration, Study)
        .join(Study, Study.id == StudyRegistration.study_id)
        .where(StudyRegistration.user_id == user.id)
    )

    return MyStudiesResponse(
        studies=[
            MyStudyResponse(
                study_id=study.id,
                name=study.name,
                duration=study.duration,
                description=study.description or "",
                status=study.status.value,
                study_group=registration.study_group.value,
                registered_at=registration.registered_at,
                consented_at=registration.consented_at,
                withdrawn_at=registration.withdrawn_at,
            )
            for registration, study in result.all()
        ]
    )


@router.get(
    "/current-script-parameters",
    response_model=CurrentStudyScriptParametersResponse,
)
async def get_current_study_script_parameters(
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> CurrentStudyScriptParametersResponse:
    """Return the most recently joined study that still governs meditation scripts."""
    result = await session.execute(
        select(StudyRegistration, Study)
        .join(Study, Study.id == StudyRegistration.study_id)
        .where(
            StudyRegistration.user_id == user.id,
            StudyRegistration.withdrawn_at.is_(None),
            Study.status.in_((StudyStatus.RECRUITING, StudyStatus.ACTIVE)),
        )
        .order_by(StudyRegistration.registered_at.desc())
        .limit(1)
    )
    current = result.first()
    if current is None:
        return CurrentStudyScriptParametersResponse(participating=False)

    _registration, study = current
    return CurrentStudyScriptParametersResponse(
        participating=True,
        study_id=study.id,
        study_status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
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

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        description=study.description or "",
        status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
    )


@router.post(
    "/{study_id}/getstatus",
    response_model=StudyStatusResponse,
)
async def get_study_status(
        study_id: UUID,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> StudyStatusResponse:
    study = await session.get(Study, study_id)
    participant_rows = await session.execute(
        select(
            StudyRegistration.study_group,
            User.id,
            User.gender,
            User.age,
        )
        .join(User, User.id == StudyRegistration.user_id)
        .where(
            StudyRegistration.study_id == study_id,
            StudyRegistration.withdrawn_at.is_(None),
        )
        .order_by(StudyRegistration.registered_at, User.id)
    )

    groups: dict[str, list[StudyParticipantStatusResponse]] = {
        group.value: [] for group in StudyGroup
    }
    for study_group, user_id, gender, age in participant_rows.all():
        groups[study_group.value].append(
            StudyParticipantStatusResponse(
                user_id=user_id,
                gender=gender,
                age=age,
            )
        )

    return StudyStatusResponse(
        name=study.name,
        duration=study.duration,
        groups=groups,
    )


@router.post(
    "/{study_id}/swapgroup",
    response_model=StudyGroupAssignmentResponse,
)
async def swap_study_group(
        study_id: UUID,
        payload: SwapStudyGroupRequest,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> StudyGroupAssignmentResponse:
    try:
        target_group = StudyGroup(payload.target_group.strip().casefold())
    except ValueError as exc:
        valid_groups = ", ".join(group.value for group in StudyGroup)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Target group must be one of: {valid_groups}",
        ) from exc

    registration = await session.get(
        StudyRegistration,
        (payload.user_id, study_id),
    )
    if registration is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User is not registered for this study",
        )

    registration.study_group = target_group
    await session.commit()

    return StudyGroupAssignmentResponse(
        study_id=study_id,
        user_id=payload.user_id,
        group=target_group.value,
    )


_EDITABLE_STUDY_COLUMNS: dict[str, type] = {
    "name": str,
    "duration": int,
    "description": str,
    "script_generation_parameters": str,
}


def _validate_study_edit(column: str, value: object) -> str | int:
    if column not in Study.__table__.columns.keys():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Study column '{column}' does not exist",
        )

    expected_type = _EDITABLE_STUDY_COLUMNS.get(column)
    if expected_type is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Study column '{column}' cannot be edited",
        )

    # Exact type checks prevent JSON booleans being accepted as integers.
    if type(value) is not expected_type:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"Study column '{column}' requires a {expected_type.__name__} value",
        )

    if column == "name":
        normalized_name = value.strip()
        if not normalized_name or len(normalized_name) > 255:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="Study name must contain between 1 and 255 characters",
            )
        return normalized_name

    if column in ("description", "script_generation_parameters"):
        normalized_text = value.strip()
        if len(normalized_text) > 10_000:
            field_name = (
                "description"
                if column == "description"
                else "script generation parameters"
            )
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail=f"Study {field_name} cannot exceed 10000 characters",
            )
        return normalized_text

    if value <= 0:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Study duration must be greater than zero",
        )
    return value


@router.post(
    "/{study_id}/edit",
    response_model=StudyResponse,
)
async def edit_study(
        study_id: UUID,
        payload: EditStudyRequest,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> StudyResponse:
    study = await session.get(Study, study_id)
    validated_value = _validate_study_edit(payload.column, payload.value)

    setattr(study, payload.column, validated_value)
    await session.commit()

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        description=study.description or "",
        status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
    )

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

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        description=study.description or "",
        status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
    )


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

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        description=study.description or "",
        status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
    )


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

    return StudyResponse(
        id=study.id,
        name=study.name,
        duration=study.duration,
        description=study.description or "",
        status=study.status.value,
        script_generation_parameters=study.script_generation_parameters or "",
    )

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

@router.post(
    "/{study_id}/leave",
    response_model=StudyRegistrationResponse,
)
async def leave_study(
        study_id: UUID,
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> StudyRegistrationResponse:
    registration = await session.get(StudyRegistration, (user.id, study_id))
    if registration is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="You are not registered for this study",
        )

    if registration.withdrawn_at is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="You have already left this study",
        )

    registration.withdrawn_at = datetime.now(timezone.utc)
    await session.commit()

    return StudyRegistrationResponse(
        user_id=registration.user_id,
        study_id=registration.study_id,
        registered_at=registration.registered_at,
        consented_at=registration.consented_at,
        withdrawn_at=registration.withdrawn_at,
    )

@router.get(
    "/{study_id}/feedback",
    response_model=FeedbackListResponse,
)
async def list_study_feedback(
        study_id: UUID,
        _owner: StudyOwnerDependency,
        session: DatabaseSessionDependency,
) -> FeedbackListResponse:
    result = await session.scalars(
        select(Feedback).where(Feedback.study_id == study_id)
    )

    return FeedbackListResponse(
        feedback=[
            FeedbackResponse(
                id=f.id,
                user_id=f.user_id,
                study_id=f.study_id,
                category=f.category.value,
                rating=f.rating,
                comment=f.comment,
                created_at=f.created_at,
            )
            for f in result
        ]
    )