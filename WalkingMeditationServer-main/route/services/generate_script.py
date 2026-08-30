from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Dict


from route.models import LatLng
from route.persistence.script_storage import save_script_text
from route.clients.openai_client import generate_text
from route.clients.google_weather import current_weather_lookup
from route.services.enrich_elevation import source_to_dest_elevation
from route.config import get_script_generation_prompt, render_template
from route.utils.script_xml import normalize_script_xml

FOCUSED_ATTENTION_SHARE = 0.2
COMPASSION_SHARE = 0.4
CLOSING_SHARE = 0.4

BASE_DIR = Path(__file__).resolve().parents[1]  # route/
DEFAULT_OUTPUT_FOLDER = BASE_DIR / "scripts"


def _words_for_minutes(minutes: float, *, wpm: int = 150) -> int:
    return int(round(minutes * wpm))


def _format_weather_condition(weather: Dict[str, Any]) -> str:
    condition = ((weather.get("weatherCondition") or {}).get("description") or {}).get("text")
    temperature = weather.get("temperature") or {}
    degrees = temperature.get("degrees")
    unit = temperature.get("unit")

    parts = [str(condition)] if condition else []
    if degrees is not None:
        suffix = "°C" if unit == "CELSIUS" else f" {unit or ''}".rstrip()
        parts.append(f"{degrees}{suffix}")
    return ", ".join(parts) or "Weather information unavailable"


async def generate_script_with_park(
    source: LatLng,
    destination: LatLng,
    park: LatLng,
    to_park_time: int,                 # seconds
    park_time: int,                    # seconds
    park_to_destination_time: int,      # seconds
    context: str,
    output_folder: Path = DEFAULT_OUTPUT_FOLDER,
    save_to_disk: bool = False,
) -> str:
    fam_words = _words_for_minutes(to_park_time / 60)
    cm_words = _words_for_minutes(park_time / 60)
    closing_words = _words_for_minutes(park_to_destination_time / 60)

    weather, source_to_park, park_to_destination = await asyncio.gather(
        asyncio.to_thread(current_weather_lookup, park.lat, park.lon),
        source_to_dest_elevation(source=source, destination=park),
        source_to_dest_elevation(source=park, destination=destination),
    )
    weather_condition = _format_weather_condition(weather)
    duration_minutes = max(1, round((to_park_time + park_time + park_to_destination_time) / 60))

    prompt_tpl = get_script_generation_prompt("WITH_PARK")
    prompt = render_template(
        prompt_tpl,
        context=context,
        duration_minutes=duration_minutes,
        FAM_word_count=fam_words,
        source_to_park="\n".join(source_to_park),
        park_to_destination="\n".join(park_to_destination),
        CM_word_count=cm_words,
        Closing_word_count=closing_words,
        weatherCondition= weather_condition,
    )

    script = normalize_script_xml(await generate_text(prompt))

    if save_to_disk:
       save_script_text(script, output_folder, prefix="meditation_script")

    return script


async def generate_script_without_park(
    source: LatLng,
    destination: LatLng,
    total_walking_time: int,   # seconds
    context: str,
    output_folder: Path = DEFAULT_OUTPUT_FOLDER,
    save_to_disk: bool = False,
) -> str:
    fam_words = _words_for_minutes(((total_walking_time * FOCUSED_ATTENTION_SHARE) / 60))
    cm_words = _words_for_minutes(((total_walking_time * COMPASSION_SHARE) / 60))
    closing_words = _words_for_minutes(((total_walking_time * CLOSING_SHARE) / 60))

    weather, total_path = await asyncio.gather(
        asyncio.to_thread(current_weather_lookup, destination.lat, destination.lon),
        source_to_dest_elevation(source=source, destination=destination),
    )
    weather_condition = _format_weather_condition(weather)
    duration_minutes = max(1, round(total_walking_time / 60))

    prompt_tpl = get_script_generation_prompt("WITHOUT_PARK")
    prompt = render_template(
        prompt_tpl,
        context=context,
        duration_minutes=duration_minutes,
        FAM_word_count=fam_words,
        source_to_park="\n".join(total_path),
        CM_word_count=cm_words,
        Closing_word_count=closing_words,
        weatherCondition=weather_condition,
    )

    script = normalize_script_xml(await generate_text(prompt))

    if save_to_disk:
       save_script_text(script, output_folder, prefix="meditation_script")

    return script
