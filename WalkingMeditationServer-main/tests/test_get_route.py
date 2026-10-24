import unittest
from unittest.mock import AsyncMock, patch

from route.models import LatLng, ParkPlace, RouteInfo
from route.services.get_route import generate_walk_route


class RouteSelectionTests(unittest.IsolatedAsyncioTestCase):
    async def test_time_limit_is_a_ceiling_and_shortest_park_routes_rank_first(self):
        source = LatLng(lat=0.0, lon=0.0)
        destination = LatLng(lat=9.0, lon=9.0)
        parks = [
            ParkPlace(place_id="long", name="Long detour", lat=1.0, lon=1.0),
            ParkPlace(place_id="short", name="Nearby park", lat=2.0, lon=2.0),
            ParkPlace(place_id="medium", name="Medium detour", lat=3.0, lon=3.0),
            ParkPlace(place_id="over-limit", name="Too far", lat=4.0, lon=4.0),
        ]
        leg_durations = {
            1.0: (1_700, 1_700),
            2.0: (400, 500),
            3.0: (600, 600),
            4.0: (2_000, 2_000),
        }

        async def fake_compute_route(client, origin, endpoint, *, want_polyline):
            if origin == source and endpoint == destination:
                return RouteInfo(
                    duration_s=600,
                    distance_m=1_000,
                    polyline="baseline",
                )

            if origin == source:
                duration = leg_durations[endpoint.lat][0]
                polyline = f"to-{endpoint.lat}"
            else:
                duration = leg_durations[origin.lat][1]
                polyline = f"from-{origin.lat}"
            return RouteInfo(duration_s=duration, distance_m=1_000, polyline=polyline)

        class FakeAsyncClient:
            async def __aenter__(self):
                return self

            async def __aexit__(self, exc_type, exc, traceback):
                return False

        search_parks = AsyncMock(return_value=parks)
        with (
            patch(
                "route.services.get_route.httpx.AsyncClient",
                return_value=FakeAsyncClient(),
            ),
            patch(
                "route.services.get_route.compute_walking_route",
                side_effect=fake_compute_route,
            ),
            patch(
                "route.services.get_route.search_parks_along_route",
                new=search_parks,
            ),
        ):
            result = await generate_walk_route(
                source,
                destination,
                total_travel_time=3_600,
            )

        search_parks.assert_awaited_once()
        self.assertEqual(3, search_parks.await_args.kwargs["max_results"])
        self.assertEqual(
            ["short", "medium", "long"],
            [option.park.place_id for option in result.parks],
        )
        self.assertEqual([900, 1_200, 3_400], [option.detour_s for option in result.parks])
        self.assertNotIn("over-limit", [option.park.place_id for option in result.parks])


if __name__ == "__main__":
    unittest.main()
