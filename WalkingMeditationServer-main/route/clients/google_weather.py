from __future__ import annotations

from typing import Any, Dict, Optional, Tuple
import requests
from route.config import google_maps_api_key 
from route.errors import GoogleAPIError

LatLon = Tuple[float, float]


def current_weather_lookup(
    lat: float,
    lon: float,
    *,
    units_system: str = "METRIC",
    language_code: str = "en",
    session: Optional[requests.Session] = None,
) -> Dict[str, Any]:
    """
    Returns "currentConditions" for a given point using Google Weather API.
    """
    url = "https://weather.googleapis.com/v1/currentConditions:lookup"
    params = {
        "key": google_maps_api_key(),
        "location.latitude": f"{lat:.6f}",
        "location.longitude": f"{lon:.6f}",
        "unitsSystem": units_system,
        "languageCode": language_code,
    }

    request_get = session.get if session is not None else requests.get
    r = request_get(url, params=params, timeout=20)
    try:
        r.raise_for_status()
    except requests.HTTPError:
        raise GoogleAPIError(
            f"Weather API request failed with status {r.status_code}"
        ) from None
    return r.json()
