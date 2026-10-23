from __future__ import annotations

import logging
from typing import Any, Dict, List

import httpx

from route.config import GOOGLE_PLACES_NEARBY_URL, google_maps_api_key
from route.models import LatLng

logger = logging.getLogger(__name__)

# Calm, walkable features worth mentioning in a meditation. Deliberately excludes
# shops, restaurants and other commercial places.
NEARBY_FEATURE_TYPES = [
    "park",
    "garden",
    "playground",
    "library",
    "museum",
    "place_of_worship",
    "tourist_attraction",
    "university",
]
NEARBY_RADIUS_M = 150.0
NEARBY_MAX_RESULTS = 3


async def search_nearby_features(
        client: httpx.AsyncClient,
        point: LatLng,
        *,
        radius_m: float = NEARBY_RADIUS_M,
        max_results: int = NEARBY_MAX_RESULTS,
) -> List[str]:
    """Return display names of notable features within ``radius_m`` of ``point``."""
    headers = {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": google_maps_api_key(),
        "X-Goog-FieldMask": "places.displayName,places.primaryType",
    }
    body: Dict[str, Any] = {
        "includedTypes": NEARBY_FEATURE_TYPES,
        "maxResultCount": max_results,
        "rankPreference": "DISTANCE",
        "locationRestriction": {
            "circle": {
                "center": {"latitude": point.lat, "longitude": point.lon},
                "radius": radius_m,
            }
        },
    }
    response = await client.post(GOOGLE_PLACES_NEARBY_URL, headers=headers, json=body)
    response.raise_for_status()

    names: List[str] = []
    for place in response.json().get("places") or []:
        name = ((place.get("displayName") or {}).get("text") or "").strip()
        if name and name not in names:
            names.append(name)
    return names
