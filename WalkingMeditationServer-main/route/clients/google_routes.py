from __future__ import annotations

from typing import Any, Dict, Optional, List

import httpx

from route.config import google_maps_api_key
from route.errors import GoogleAPIError
from route.models import LatLng, RouteInfo, RouteStepInfo
from route.utils.utils import duration_to_seconds, latlng_waypoint

ROUTES_COMPUTE_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"



async def compute_walking_route(
    client: httpx.AsyncClient,
    origin: LatLng,
    destination: LatLng,
    *,
    intermediate: Optional[LatLng] = None,
    want_polyline: bool = True,
) -> RouteInfo:
    headers = {
        "X-Goog-Api-Key": google_maps_api_key(),
        "Content-Type": "application/json",
        "X-Goog-FieldMask": (
            "routes.duration,routes.distanceMeters"
            + (",routes.polyline.encodedPolyline" if want_polyline else "")
            + (
                ",routes.legs.steps.startLocation,"
                "routes.legs.steps.endLocation,"
                "routes.legs.steps.distanceMeters,"
                "routes.legs.steps.staticDuration,"
                "routes.legs.steps.polyline.encodedPolyline,"
                "routes.legs.steps.navigationInstruction.instructions"
                if want_polyline else ""
            )
        ),
    }

    body: Dict[str, Any] = {
        "origin": latlng_waypoint(origin.lat, origin.lon),
        "destination": latlng_waypoint(destination.lat, destination.lon),
        "travelMode": "WALK",
        "computeAlternativeRoutes": False,
    }
    if intermediate:
        body["intermediates"] = [latlng_waypoint(intermediate.lat, intermediate.lon)]

    r = await client.post(ROUTES_COMPUTE_URL, headers=headers, json=body)
    r.raise_for_status()
    data = r.json()

    routes = data.get("routes") or []
    if not routes:
        raise GoogleAPIError("No routes returned from computeRoutes")

    first = routes[0]
    dur_s = duration_to_seconds(first.get("duration"))
    dist_m = int(first.get("distanceMeters", 0))

    poly = ""
    if want_polyline:
        poly = (first.get("polyline") or {}).get("encodedPolyline") or ""
        if not poly:
            raise GoogleAPIError("Expected polyline.encodedPolyline but got empty.")

    steps: List[RouteStepInfo] = []
    if want_polyline:
        for leg in first.get("legs") or []:
            for raw_step in leg.get("steps") or []:
                start = (raw_step.get("startLocation") or {}).get("latLng") or {}
                end = (raw_step.get("endLocation") or {}).get("latLng") or {}
                step_polyline = (
                    (raw_step.get("polyline") or {}).get("encodedPolyline") or ""
                )
                if (
                    not step_polyline
                    or "latitude" not in start
                    or "longitude" not in start
                    or "latitude" not in end
                    or "longitude" not in end
                ):
                    continue
                static_duration = raw_step.get("staticDuration")
                steps.append(
                    RouteStepInfo(
                        index=len(steps) + 1,
                        instruction=(
                            (raw_step.get("navigationInstruction") or {}).get(
                                "instructions"
                            )
                            or "Continue"
                        ).strip(),
                        start=LatLng(
                            lat=float(start["latitude"]),
                            lon=float(start["longitude"]),
                        ),
                        end=LatLng(
                            lat=float(end["latitude"]),
                            lon=float(end["longitude"]),
                        ),
                        duration_s=(
                            duration_to_seconds(static_duration)
                            if static_duration else 0
                        ),
                        distance_m=max(0, int(raw_step.get("distanceMeters") or 0)),
                        polyline=step_polyline,
                    )
                )

    return RouteInfo(
        duration_s=dur_s,
        distance_m=dist_m,
        polyline=poly,
        steps=steps,
    )



async def fetch_route_polyline(
    client: httpx.AsyncClient,
    origin: LatLng,
    dest: LatLng,
) -> str:
    headers = {
        "X-Goog-Api-Key": google_maps_api_key(),
        "Content-Type": "application/json",
        "X-Goog-FieldMask": "routes.polyline.encodedPolyline",
    }
    body: Dict[str, Any] = {
        "origin": latlng_waypoint(origin.lat, origin.lon),
        "destination": latlng_waypoint(dest.lat, dest.lon),
        "travelMode": "WALK",
        "computeAlternativeRoutes": False,
    }

    r = await client.post(ROUTES_COMPUTE_URL, headers=headers, json=body)
    r.raise_for_status()
    data = r.json()

    routes = data.get("routes") or []
    poly = (((routes[0].get("polyline") or {}).get("encodedPolyline")) if routes else "") or ""
    if not poly:
        raise GoogleAPIError("Expected routes.polyline.encodedPolyline but got empty")
    return poly
