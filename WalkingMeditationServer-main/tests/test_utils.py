import unittest

from route.errors import GoogleRoutesError
from route.utils.utils import duration_to_seconds


class DurationToSecondsTests(unittest.TestCase):
    def test_integer_duration(self):
        self.assertEqual(123, duration_to_seconds("123s"))

    def test_fractional_duration_rounds_up(self):
        self.assertEqual(4, duration_to_seconds("3.5s"))
        self.assertEqual(1, duration_to_seconds("0.000000001s"))

    def test_rejects_invalid_duration(self):
        for value in ("", "3", "3ms", "-1s", "1.1234567890s"):
            with self.subTest(value=value):
                with self.assertRaises(GoogleRoutesError):
                    duration_to_seconds(value)


if __name__ == "__main__":
    unittest.main()
