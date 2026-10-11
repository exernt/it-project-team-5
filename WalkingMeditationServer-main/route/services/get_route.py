from __future__ import annotations

import asyncio
import httpx
import logging
from typing import Optional

from route.clients.google_places import search_parks_along_route
from route.clients.google_routes import compute_walking_route
from route.models import LatLng, ParkDetourResult, ParkPlace, WalkRouteResponse

logger = logging.getLogger(__name__)

async def generate_walk_route(
    source: LatLng,
    destination: LatLng,
    total_travel_time: int,
    *,
    sar_max_parks: int = 25,
    concurrency: int = 6,
    timeout_s: float = 40.0,
) -> WalkRouteResponse:
    async with httpx.AsyncClient(timeout=timeout_s) as client:
        baseline = await compute_walking_route(
            client, source, destination, want_polyline=True
        )

        if baseline.duration_s > total_travel_time:
            return WalkRouteResponse(
                origin=source,
                destination=destination,
                total_travel_time=total_travel_time,
                baseline=baseline,
                parks=[],
            )

        parks = await search_parks_along_route(
            client,
            route_polyline=baseline.polyline,
            max_results=sar_max_parks,
        )

        if not parks:
            return WalkRouteResponse(
                origin=source,
                destination=destination,
                total_travel_time=total_travel_time,
                baseline=baseline,
                parks=[],
            )

        route_sem = asyncio.Semaphore(concurrency)

        async def compute_leg(start: LatLng, end: LatLng):
            async with route_sem:
                return await compute_walking_route(
                    client, start, end, want_polyline=True
                )

        async def eval_one(p: ParkPlace) -> Optional[ParkDetourResult]:
            try:
                park_location = LatLng(lat=p.lat, lon=p.lon)
                to_park, from_park = await asyncio.gather(
                    compute_leg(source, park_location),
                    compute_leg(park_location, destination),
                )
            except Exception:
                logger.warning("Unable to evaluate park %s", p.place_id, exc_info=True)
                return None

            detour_s = to_park.duration_s + from_park.duration_s
            if detour_s <= total_travel_time:
                return ParkDetourResult(
                    park=p,
                    baseline_s=baseline.duration_s,
                    detour_s=detour_s,
                    to_park_s=to_park.duration_s,
                    park_to_destination_s=from_park.duration_s,
                    added_s=detour_s - baseline.duration_s,
                    slack_s=total_travel_time - detour_s,
                    polyline_to_park=to_park.polyline,
                    polyline_from_park=from_park.polyline,
                    steps_to_park=to_park.steps,
                    steps_from_park=from_park.steps,
                )
            return None

        evaluated = await asyncio.gather(*(eval_one(p) for p in parks))
        results = [r for r in evaluated if r is not None]

        # The time limit is only a hard ceiling. Among feasible park routes,
        # prefer the shortest walk from source to destination via the park.
        # Python's stable sort preserves the Places result order for ties.
        results.sort(key=lambda r: r.detour_s)

        return WalkRouteResponse(
            origin=source,
            destination=destination,
            total_travel_time=total_travel_time,
            baseline=baseline,
            parks=results[:3],
        )
