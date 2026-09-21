import os
import unittest
from uuid import UUID, uuid4

from sqlalchemy import CheckConstraint, UniqueConstraint
from sqlalchemy.exc import IntegrityError

from database.core import create_configured_database
from database.models import User


class UserModelTests(unittest.TestCase):
    def test_users_table_shape(self):
        table = User.__table__

        self.assertEqual("users", table.name)
        self.assertEqual(
            {
                "id",
                "email",
                "password_hash",
                "gender",
                "age",
                "role",
                "email_verified",
                "created_on",
            },
            set(table.columns.keys()),
        )
        self.assertTrue(table.c.id.primary_key)
        self.assertFalse(table.c.email.nullable)
        self.assertFalse(table.c.password_hash.nullable)
        self.assertTrue(table.c.gender.nullable)
        self.assertTrue(table.c.age.nullable)
        self.assertFalse(table.c.email_verified.nullable)
        self.assertFalse(table.c.created_on.nullable)
        self.assertTrue(
            any(
                isinstance(constraint, UniqueConstraint)
                and constraint.name == "uq_users_email"
                for constraint in table.constraints
            )
        )
        self.assertTrue(
            any(
                isinstance(constraint, CheckConstraint)
                and constraint.name == "ck_users_age_range"
                for constraint in table.constraints
            )
        )


@unittest.skipUnless(
    os.getenv("RUN_DATABASE_INTEGRATION_TESTS") == "1",
    "set RUN_DATABASE_INTEGRATION_TESTS=1 to use a live PostgreSQL database",
)
class UserModelIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.database = create_configured_database()
        if self.database is None:
            self.fail("DATABASE_URL is required for database integration tests")

    async def asyncTearDown(self):
        await self.database.close()

    async def test_database_generates_user_defaults(self):
        email = f"defaults-{uuid4()}@example.com"

        async with self.database.session() as session:
            user = User(email=email, password_hash="test-only-hash")
            session.add(user)
            await session.flush()

            self.assertIsInstance(user.id, UUID)
            self.assertFalse(user.email_verified)
            self.assertIsNotNone(user.created_on)
            self.assertIsNotNone(user.created_on.tzinfo)

            await session.rollback()

    async def test_database_rejects_duplicate_emails(self):
        email = f"duplicate-{uuid4()}@example.com"

        async with self.database.session() as session:
            session.add(User(email=email, password_hash="first-hash"))
            await session.flush()
            session.add(User(email=email, password_hash="second-hash"))

            with self.assertRaises(IntegrityError):
                await session.flush()

            await session.rollback()


if __name__ == "__main__":
    unittest.main()
