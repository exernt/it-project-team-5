import os
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException

from route.config import (
    database_connect_timeout_seconds,
    database_max_overflow,
    database_pool_size,
    database_url,
)
from database.core import Database, create_configured_database, get_database


class DatabaseConfigTests(unittest.TestCase):
    def test_database_url_is_optional_and_trimmed(self):
        with patch.dict(os.environ, {"DATABASE_URL": "   "}):
            self.assertIsNone(database_url())

        with patch.dict(
            os.environ,
            {"DATABASE_URL": "  postgresql+asyncpg://user:pass@db/app  "},
        ):
            self.assertEqual(
                "postgresql+asyncpg://user:pass@db/app",
                database_url(),
            )

    def test_pool_configuration_validation(self):
        with patch.dict(
            os.environ,
            {
                "DATABASE_POOL_SIZE": "0",
                "DATABASE_MAX_OVERFLOW": "-1",
                "DATABASE_CONNECT_TIMEOUT_SECONDS": "not-a-number",
            },
        ):
            with self.assertRaises(ValueError):
                database_pool_size()
            with self.assertRaises(ValueError):
                database_max_overflow()
            with self.assertRaises(ValueError):
                database_connect_timeout_seconds()

    def test_no_runtime_is_created_without_a_url(self):
        with patch.dict(os.environ, {"DATABASE_URL": ""}):
            self.assertIsNone(create_configured_database())


class DatabaseRuntimeTests(unittest.IsolatedAsyncioTestCase):
    @patch("database.core.create_async_engine")
    def test_database_requires_the_async_postgresql_driver(self, create_engine):
        with self.assertRaisesRegex(ValueError, "postgresql\\+asyncpg"):
            Database("sqlite+aiosqlite:///test.db")
        create_engine.assert_not_called()

    async def test_session_rolls_back_when_work_fails(self):
        session = AsyncMock()
        session.__aenter__.return_value = session
        session.__aexit__.return_value = None

        database = object.__new__(Database)
        database._session_factory = MagicMock(return_value=session)

        with self.assertRaisesRegex(RuntimeError, "failed"):
            async with database.session():
                raise RuntimeError("failed")

        session.rollback.assert_awaited_once_with()

    def test_unconfigured_request_dependency_returns_503(self):
        request = MagicMock()
        request.app.state.database = None

        with self.assertRaises(HTTPException) as raised:
            get_database(request)

        self.assertEqual(503, raised.exception.status_code)


@unittest.skipUnless(
    os.getenv("RUN_DATABASE_INTEGRATION_TESTS") == "1",
    "set RUN_DATABASE_INTEGRATION_TESTS=1 to use a live PostgreSQL database",
)
class DatabaseIntegrationTests(unittest.TestCase):
    def test_fastapi_database_health_endpoint(self):
        from fastapi.testclient import TestClient

        from main import app

        with TestClient(app) as client:
            response = client.get("/health/database")

        self.assertEqual(200, response.status_code)
        self.assertEqual({"database": "ready"}, response.json())


if __name__ == "__main__":
    unittest.main()
