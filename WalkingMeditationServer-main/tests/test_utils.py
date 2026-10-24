import unittest
from unittest.mock import patch

from route.config import park_fallback_enabled
from route.clients.google_places import search_parks_along_route
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


class ParkFallbackConfigTests(unittest.TestCase):
    def test_fallback_is_disabled_by_default(self):
        with patch.dict("os.environ", {}, clear=True):
            self.assertFalse(park_fallback_enabled())

    def test_truthy_values_enable_fallback(self):
        for value in ("true", "1", "yes", "on", " TRUE "):
            with self.subTest(value=value), patch.dict(
                "os.environ", {"ENABLE_PARK_FALLBACK": value}, clear=True
            ):
                self.assertTrue(park_fallback_enabled())

    def test_invalid_value_is_rejected(self):
        with patch.dict(
            "os.environ", {"ENABLE_PARK_FALLBACK": "sometimes"}, clear=True
        ):
            with self.assertRaises(ValueError):
                park_fallback_enabled()


class _PlacesResponse:
    def __init__(self, place_type):
        self.place_type = place_type

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "places": [
                {
                    "id": f"{self.place_type}-id",
                    "displayName": {"text": self.place_type},
                    "location": {"latitude": -37.8, "longitude": 144.9},
                    "primaryType": self.place_type,
                    "types": [self.place_type],
                }
            ]
        }


class _PlacesClient:
    def __init__(self):
        self.requested_types = []

    async def post(self, url, *, headers, json):
        place_type = json["includedType"]
        self.requested_types.append(place_type)
        return _PlacesResponse(place_type)


class ParkFallbackSearchTests(unittest.IsolatedAsyncioTestCase):
    async def test_disabled_fallback_searches_city_parks_and_gardens(self):
        client = _PlacesClient()
        with patch.dict(
            "os.environ",
            {"GOOGLE_MAPS_API_KEY": "test", "ENABLE_PARK_FALLBACK": "false"},
            clear=True,
        ):
            parks = await search_parks_along_route(client, route_polyline="encoded")

        self.assertEqual(["city_park", "garden"], client.requested_types)
        self.assertEqual(
            ["city_park-id", "garden-id"],
            [park.place_id for park in parks],
        )

    async def test_enabled_fallback_supplements_city_parks_and_gardens(self):
        client = _PlacesClient()
        with patch.dict(
            "os.environ",
            {"GOOGLE_MAPS_API_KEY": "test", "ENABLE_PARK_FALLBACK": "true"},
            clear=True,
        ):
            parks = await search_parks_along_route(client, route_polyline="encoded")

        self.assertEqual(["city_park", "garden", "park"], client.requested_types)
        self.assertEqual(
            ["city_park-id", "garden-id", "park-id"],
            [park.place_id for park in parks],
        )


if __name__ == "__main__":
    unittest.main()
