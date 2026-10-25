"""Manual check of script generation using only an OpenAI key.

Google weather, route steps, elevation and nearby-places lookups are replaced
with fixed Melbourne data. The OpenAI call is real and costs a few cents at most.

Run from WalkingMeditationServer-main:
    python scripts/try_openai_script.py
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Exercise the nearby-context path; the Places call itself is faked below.
os.environ["ENABLE_NEARBY_CONTEXT"] = "true"

from route.config import openai_api_key  # noqa: E402  (loads .env)
from route.models import LatLng, StepSegment  # noqa: E402
from route.services.generate_script import generate_script_without_park  # noqa: E402

FAKE_SEGMENTS = [
    StepSegment(
        i=1,
        instruction="Head north on Swanston St",
        start=LatLng(lat=-37.8136, lon=144.9631),
        end=LatLng(lat=-37.8110, lon=144.9660),
        step_polyline="fake",
        duration_s=240,
        distance_m=320,
        elev_start_m=28.0,
        elev_end_m=33.0,
        elev_gain_m=5.0,
        label="high elevation (uphill)",
    ),
    StepSegment(
        i=2,
        instruction="Turn right onto Bourke St",
        start=LatLng(lat=-37.8110, lon=144.9660),
        end=LatLng(lat=-37.8100, lon=144.9700),
        step_polyline="fake",
        duration_s=360,
        distance_m=450,
        elev_start_m=33.0,
        elev_end_m=31.0,
        elev_gain_m=-2.0,
        label="flat",
    ),
]


async def main() -> None:
    openai_api_key()  # fails fast with a clear message if the key is missing

    with patch(
        "route.services.generate_script.current_weather_lookup",
        return_value={
            "weatherCondition": {"description": {"text": "Partly cloudy"}},
            "temperature": {"degrees": 19, "unit": "CELSIUS"},
        },
    ), patch(
        "route.services.generate_script.build_route_elevation_segments",
        new_callable=AsyncMock,
        return_value=FAKE_SEGMENTS,
    ), patch(
        "route.services.generate_script.search_nearby_features",
        new_callable=AsyncMock,
        side_effect=[["Parliament Gardens"], []],
    ):
        result = await generate_script_without_park(
            LatLng(lat=-37.8136, lon=144.9631),
            LatLng(lat=-37.8100, lon=144.9700),
            600,
            "general wellbeing",
            "Use short sentences.",
        )

    script = result.script
    print(f"model={script.model} prompt_version={script.prompt_version}")
    print(f"cost_usd={result.usage.cost_usd} tokens={result.usage.total_tokens}\n")
    for section in script.sections:
        print(f"== {section.type} (min words {section.minimum_words})")
        for segment in getattr(section, "route_segments", []):
            print(f"[{segment.id}] nearby={segment.nearby_features}")
            print(f"  {segment.narration}\n")
    print(json.dumps(script.model_dump(mode="json"), indent=2)[:1500], "...")


if __name__ == "__main__":
    asyncio.run(main())
