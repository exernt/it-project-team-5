from __future__ import annotations

from typing import Any, Dict, List

import httpx

from route.config import google_maps_api_key, park_fallback_enabled
from route.models import ParkPlace

PLACES_TEXTSEARCH_NEW_URL = "https://places.googleapis.com/v1/places:searchText"


async def search_parks_along_route(
    client: httpx.AsyncClient,
    *,
    route_polyline: str,
    max_results: int = 20,
) -> List[ParkPlace]:
    """Find city parks and gardens, optionally adding generic parks."""
    city_parks = await _search_parks_by_type(
        client,
        route_polyline=route_polyline,
        place_type="city_park",
        text_query="city park",
    )
    gardens = await _search_parks_by_type(
        client,
        route_polyline=route_polyline,
        place_type="garden",
        text_query="garden",
    )

    results = city_parks + gardens
    if park_fallback_enabled():
        generic_parks = await _search_parks_by_type(
            client,
            route_polyline=route_polyline,
            place_type="park",
            text_query="park",
        )
        results += generic_parks

    unique: Dict[str, ParkPlace] = {park.place_id: park for park in results}
    return list(unique.values())[:max_results]


async def _search_parks_by_type(
    client: httpx.AsyncClient,
    *,
    route_polyline: str,
    place_type: str,
    text_query: str,
) -> List[ParkPlace]:
    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": google_maps_api_key(),
        "X-Goog-FieldMask": (
            "places.id,places.displayName,places.formattedAddress,places.location,"
            "places.primaryType,places.types"
        ),
    }

    body: Dict[str, Any] = {
        "textQuery": text_query,
        "includedType": place_type,
        "strictTypeFiltering": True,
        "searchAlongRouteParameters": {
            "polyline": {"encodedPolyline": route_polyline},
        },
    }

    response = await client.post(PLACES_TEXTSEARCH_NEW_URL, headers=headers, json=body)
    response.raise_for_status()
    data = response.json()

    results: List[ParkPlace] = []
    for place in data.get("places") or []:
        place_id = place.get("id") or ""
        if not place_id:
            continue

        location = place.get("location") or {}
        lat = location.get("latitude")
        lon = location.get("longitude")
        if lat is None or lon is None:
            continue

        results.append(
            ParkPlace(
                place_id=place_id,
                name=(place.get("displayName") or {}).get("text") or "(unnamed park)",
                lat=float(lat),
                lon=float(lon),
                primary_type=place.get("primaryType"),
                types=list(place.get("types") or []),
            )
        )

    return results
