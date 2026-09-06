from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from route.config import (
    database_connect_timeout_seconds,
    database_max_overflow,
    database_pool_size,
    database_url,
)


class Base(DeclarativeBase):
    """Base class shared by the application's ORM models."""


class Database:
    """Own the SQLAlchemy engine, connection pool, and session factory."""

    def __init__(
        self,
        url: str,
        *,
        pool_size: int = 5,
        max_overflow: int = 5,
        connect_timeout_seconds: int = 5,
    ) -> None:
        parsed_url = make_url(url)
        if parsed_url.drivername != "postgresql+asyncpg":
            raise ValueError(
                "DATABASE_URL must use the postgresql+asyncpg SQLAlchemy driver"
            )

        self.engine: AsyncEngine = create_async_engine(
            parsed_url,
            pool_pre_ping=True,
            pool_size=pool_size,
            max_overflow=max_overflow,
            connect_args={"timeout": connect_timeout_seconds},
        )
        self._session_factory = async_sessionmaker(
            bind=self.engine,
            class_=AsyncSession,
            expire_on_commit=False,
        )

    @asynccontextmanager
    async def session(self) -> AsyncIterator[AsyncSession]:
        """Yield one request-scoped session without committing implicitly."""
        async with self._session_factory() as session:
            try:
                yield session
            except Exception:
                await session.rollback()
                raise

    async def ping(self) -> None:
        """Open a pooled connection and verify that PostgreSQL answers a query."""
        async with self.engine.connect() as connection:
            await connection.execute(text("SELECT 1"))

    async def close(self) -> None:
        await self.engine.dispose()


def create_configured_database() -> Database | None:
    """Build the database runtime, or return None when it is not configured."""
    url = database_url()
    if url is None:
        return None

    return Database(
        url,
        pool_size=database_pool_size(),
        max_overflow=database_max_overflow(),
        connect_timeout_seconds=database_connect_timeout_seconds(),
    )


def get_database(request: Request) -> Database:
    database: Database | None = getattr(request.app.state, "database", None)
    if database is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Database is not configured",
        )
    return database


async def get_db_session(
    database: Annotated[Database, Depends(get_database)],
) -> AsyncIterator[AsyncSession]:
    """FastAPI dependency for a single, request-scoped database session."""
    async with database.session() as session:
        yield session


DatabaseDependency = Annotated[Database, Depends(get_database)]
DatabaseSessionDependency = Annotated[AsyncSession, Depends(get_db_session)]
