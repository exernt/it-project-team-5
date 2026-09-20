from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select

from database.models import Feedback, Study
from database.core import DatabaseSessionDependency
from route.dependencies import CurrentUserDependency, StudyOwnerDependency
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
        user_id=user.id,
        study_id=payload.study_id,
        category=payload.category,
        rating=payload.rating,
        comment=payload.comment,
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