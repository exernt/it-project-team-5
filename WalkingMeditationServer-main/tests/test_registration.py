import unittest
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

from argon2 import PasswordHasher, Type
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.exc import IntegrityError

from route.auth import register_user
from route.models import RegisterRequest
from route.services.passwords import hash_password


class RegistrationRequestTests(unittest.TestCase):
    def test_normalizes_email(self):
        request = RegisterRequest(
            email="  Alice.Example@EXAMPLE.COM  ",
            password="correct horse battery staple",
        )

        self.assertEqual("alice.example@example.com", str(request.email))

    def test_rejects_short_password(self):
        with self.assertRaises(ValidationError):
            RegisterRequest(email="alice@example.com", password="short")

    def test_normalizes_optional_demographics(self):
        request = RegisterRequest(
            email="alice@example.com",
            password="correct horse battery staple",
            gender="  non-binary  ",
            age=31,
        )

        self.assertEqual("non-binary", request.gender)
        self.assertEqual(31, request.age)

    def test_rejects_invalid_age(self):
        with self.assertRaises(ValidationError):
            RegisterRequest(
                email="alice@example.com",
                password="correct horse battery staple",
                age=151,
            )


class PasswordHashTests(unittest.IsolatedAsyncioTestCase):
    async def test_hashes_with_argon2id_and_embeds_verification_data(self):
        encoded_hash = await hash_password("correct horse battery staple")

        self.assertTrue(encoded_hash.startswith("$argon2id$"))
        verifier = PasswordHasher(type=Type.ID)
        self.assertTrue(
            verifier.verify(encoded_hash, "correct horse battery staple")
        )


class RegistrationServiceTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.session = MagicMock()
        self.session.scalar = AsyncMock()
        self.session.commit = AsyncMock()
        self.session.rollback = AsyncMock()

    @patch("route.auth.hash_password", new_callable=AsyncMock)
    async def test_inserts_user_into_users_table(self, hash_password_mock):
        self.session.scalar.return_value = None
        hash_password_mock.return_value = "$argon2id$test-only-hash"
        payload = RegisterRequest(
            email="  New.User@Example.COM ",
            password="valid password",
            gender="woman",
            age=28,
        )

        response = await register_user(payload, self.session)

        self.assertEqual("Registration successful", response.message)
        inserted_user = self.session.add.call_args.args[0]
        self.assertEqual("users", inserted_user.__tablename__)
        self.assertEqual("new.user@example.com", inserted_user.email)
        self.assertEqual("$argon2id$test-only-hash", inserted_user.password_hash)
        self.assertEqual("woman", inserted_user.gender)
        self.assertEqual(28, inserted_user.age)
        self.session.commit.assert_awaited_once()

    @patch("route.auth.hash_password", new_callable=AsyncMock)
    async def test_rejects_existing_normalized_email(self, hash_password_mock):
        self.session.scalar.return_value = uuid4()
        payload = RegisterRequest(
            email="Existing@Example.com",
            password="valid password",
        )

        with self.assertRaises(HTTPException) as raised:
            await register_user(payload, self.session)

        self.assertEqual(409, raised.exception.status_code)
        self.assertEqual("Email is already registered", raised.exception.detail)
        hash_password_mock.assert_not_awaited()
        self.session.add.assert_not_called()
        self.session.commit.assert_not_awaited()

    @patch("route.auth.hash_password", new_callable=AsyncMock)
    async def test_unique_constraint_handles_concurrent_duplicate(self, hash_password_mock):
        self.session.scalar.return_value = None
        hash_password_mock.return_value = "$argon2id$test-only-hash"
        self.session.commit.side_effect = IntegrityError(
            "INSERT INTO users",
            {},
            Exception("duplicate key"),
        )
        payload = RegisterRequest(
            email="race@example.com",
            password="valid password",
        )

        with self.assertRaises(HTTPException) as raised:
            await register_user(payload, self.session)

        self.assertEqual(409, raised.exception.status_code)
        self.session.rollback.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
