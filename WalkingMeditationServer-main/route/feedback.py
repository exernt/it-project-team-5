from __future__ import annotations

from uuid import uuid4
from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select
from datetime import datetime, timezone
from database.models import Feedback, FeedbackCategory, Study
from database.core import DatabaseSessionDependency
from route.dependencies import AdminOrVerifiedResearcherDependency, CurrentUserDependency
from route.models import (
    FeedbackListResponse,
    FeedbackResponse,
    SubmitFeedbackRequest,
)

router = APIRouter(prefix="/feedback", tags=["feedback"])


@router.post(
    "",
    response_model=FeedbackResponse,
    status_code=status.HTTP_201_CREATED,
)
async def submit_feedback(
        payload: SubmitFeedbackRequest,
        user: CurrentUserDependency,
        session: DatabaseSessionDependency,
) -> FeedbackResponse:
    if payload.study_id is not None:
        study = await session.get(Study, payload.study_id)
        if study is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Study not found",
            )

    feedback = Feedback(
        id=uuid4(),
        user_id=user.id,
        study_id=payload.study_id,
        category=FeedbackCategory(payload.category),
        rating=payload.rating,
        comment=payload.comment,
        created_at=datetime.now(timezone.utc),
    )
    session.add(feedback)
    await session.commit()

    return FeedbackResponse(
        id=feedback.id,
        user_id=feedback.user_id,
        study_id=feedback.study_id,
        category=feedback.category.value,
        rating=feedback.rating,
        comment=feedback.comment,
        created_at=feedback.created_at,
    )


@router.get(
    "",
    response_model=FeedbackListResponse,
)
async def list_general_feedback(
        _viewer: AdminOrVerifiedResearcherDependency,
        session: DatabaseSessionDependency,
) -> FeedbackListResponse:
    """List feedback that isn't tied to any study. Admin or verified
    researcher only, since this can include feedback from participants of
    any study."""
    result = await session.scalars(
        select(Feedback)
        .where(Feedback.study_id.is_(None))
        .order_by(Feedback.created_at.desc())
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