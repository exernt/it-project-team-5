from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Sequence

from route.clients.google_weather import current_weather_lookup
from route.clients.openai_client import GenerationUsage, generate_text
from route.config import get_script_generation_prompt, render_template
from route.models import (
    LatLng,
    MeditationScript,
    ScriptCompassionSection,
    ScriptElevation,
    ScriptInputs,
    ScriptNarration,
    ScriptRouteSegment,
    ScriptWalkingSection,
    RouteStepInfo,
    StepSegment,
)
from route.persistence.script_storage import save_script_text
from route.services.enrich_elevation import build_route_elevation_segments
from route.utils.route_hint import extract_street_hint

FOCUSED_ATTENTION_SHARE = 0.2
COMPASSION_SHARE = 0.4
CLOSING_SHARE = 0.4

BASE_DIR = Path(__file__).resolve().parents[1]  # route/
DEFAULT_OUTPUT_FOLDER = BASE_DIR / "scripts"


@dataclass(frozen=True)
class GeneratedScript:
    script: MeditationScript
    usage: GenerationUsage


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


def _segment_id(route_phase: str, segment: StepSegment) -> str:
    return f"{route_phase}_{segment.i}"


def _route_context(segments: Sequence[StepSegment], route_phase: str) -> str:
    return json.dumps(
        [
            {
                "segment_id": _segment_id(route_phase, segment),
                "route_label": extract_street_hint(segment.instruction),
                "instruction": segment.instruction,
                "terrain": segment.label,
            }
            for segment in segments
        ],
        ensure_ascii=False,
        indent=2,
    )


def _segment_narrations_schema(segment_ids: Sequence[str]) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            segment_id: {
                "type": "string",
                "description": f"Narration for route segment {segment_id}.",
            }
            for segment_id in segment_ids
        },
        "required": list(segment_ids),
        "additionalProperties": False,
    }


def _walking_section_schema(segment_ids: Sequence[str]) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "opening_narration": {"type": "string"},
            "segment_narrations": _segment_narrations_schema(segment_ids),
            "closing_narration": {"type": "string"},
        },
        "required": [
            "opening_narration",
            "segment_narrations",
            "closing_narration",
        ],
        "additionalProperties": False,
    }


def _narration_schema(
    focused_segment_ids: Sequence[str],
    closing_segment_ids: Sequence[str],
) -> Dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "focused_attention": _walking_section_schema(focused_segment_ids),
            "compassion_meditation": {
                "type": "object",
                "properties": {"narration": {"type": "string"}},
                "required": ["narration"],
                "additionalProperties": False,
            },
            "closing_meditation": _walking_section_schema(closing_segment_ids),
        },
        "required": [
            "focused_attention",
            "compassion_meditation",
            "closing_meditation",
        ],
        "additionalProperties": False,
    }


def _parse_narrations(
    generated_text: str,
    focused_segment_ids: Sequence[str],
    closing_segment_ids: Sequence[str],
) -> Dict[str, Any]:
    try:
        value = json.loads(generated_text)
    except json.JSONDecodeError as exc:
        raise ValueError("Generated meditation narration is not valid JSON") from exc

    if not isinstance(value, dict):
        raise ValueError("Generated meditation narration must be a JSON object")

    expected_segment_ids = {
        "focused_attention": set(focused_segment_ids),
        "closing_meditation": set(closing_segment_ids),
    }
    try:
        narration_values = [
            value["focused_attention"]["opening_narration"],
            value["focused_attention"]["closing_narration"],
            value["compassion_meditation"]["narration"],
            value["closing_meditation"]["opening_narration"],
            value["closing_meditation"]["closing_narration"],
        ]
        for section_name, expected_ids in expected_segment_ids.items():
            actual = value[section_name]["segment_narrations"]
            if not isinstance(actual, dict) or set(actual) != expected_ids:
                raise ValueError(
                    f"Generated {section_name} segment IDs do not match the route"
                )
            narration_values.extend(actual.values())
    except (KeyError, TypeError) as exc:
        raise ValueError("Generated meditation narration has an invalid structure") from exc

    if any(not isinstance(text, str) or not text.strip() for text in narration_values):
        raise ValueError("Generated meditation narration contains an empty text block")
    return value


def _attach_route_metadata(
    segments: Sequence[StepSegment],
    route_phase: str,
    narrations: Dict[str, str],
) -> List[ScriptRouteSegment]:
    return [
        ScriptRouteSegment(
            id=_segment_id(route_phase, segment),
            route_phase=route_phase,
            index=segment.i,
            instruction=segment.instruction,
            route_label=extract_street_hint(segment.instruction),
            start=segment.start,
            end=segment.end,
            polyline=segment.step_polyline,
            duration_s=segment.duration_s,
            distance_m=segment.distance_m,
            elevation=ScriptElevation(
                classification=segment.label,
                gain_m=round(segment.elev_gain_m, 1),
                start_m=round(segment.elev_start_m, 1),
                end_m=round(segment.elev_end_m, 1),
            ),
            narration=narrations[_segment_id(route_phase, segment)].strip(),
        )
        for segment in segments
    ]


def _assemble_script(
    *,
    duration_minutes: int,
    context: str,
    weather_condition: str,
    source: LatLng,
    destination: LatLng,
    park: LatLng | None,
    focused_words: int,
    compassion_words: int,
    closing_words: int,
    focused_segments: Sequence[StepSegment],
    focused_route_phase: str,
    closing_segments: Sequence[StepSegment],
    closing_route_phase: str,
    narrations: Dict[str, Any],
) -> MeditationScript:
    focused = narrations["focused_attention"]
    compassion = narrations["compassion_meditation"]
    closing = narrations["closing_meditation"]

    return MeditationScript(
        duration_minutes=duration_minutes,
        language="simple",
        includes_pauses=True,
        inputs=ScriptInputs(
            context=context,
            weather_condition=weather_condition,
            source=source,
            destination=destination,
            park=park,
        ),
        sections=[
            ScriptWalkingSection(
                type="focused_attention",
                minimum_words=focused_words,
                opening_narration=ScriptNarration(
                    id="focused_opening",
                    text=focused["opening_narration"].strip(),
                ),
                route_segments=_attach_route_metadata(
                    focused_segments,
                    focused_route_phase,
                    focused["segment_narrations"],
                ),
                closing_narration=ScriptNarration(
                    id="focused_closing",
                    text=focused["closing_narration"].strip(),
                ),
            ),
            ScriptCompassionSection(
                type="compassion_meditation",
                minimum_words=compassion_words,
                narration=ScriptNarration(
                    id="compassion",
                    text=compassion["narration"].strip(),
                ),
            ),
            ScriptWalkingSection(
                type="closing_meditation",
                minimum_words=closing_words,
                opening_narration=ScriptNarration(
                    id="closing_opening",
                    text=closing["opening_narration"].strip(),
                ),
                route_segments=_attach_route_metadata(
                    closing_segments,
                    closing_route_phase,
                    closing["segment_narrations"],
                ),
                closing_narration=ScriptNarration(
                    id="closing_closing",
                    text=closing["closing_narration"].strip(),
                ),
            ),
        ],
    )


async def generate_script_with_park(
    source: LatLng,
    destination: LatLng,
    park: LatLng,
    to_park_time: int,
    park_time: int,
    park_to_destination_time: int,
    context: str,
    study_parameters: str = "",
    source_to_park_steps: Sequence[RouteStepInfo] | None = None,
    park_to_destination_steps: Sequence[RouteStepInfo] | None = None,
    output_folder: Path = DEFAULT_OUTPUT_FOLDER,
    save_to_disk: bool = False,
) -> GeneratedScript:
    focused_words = _words_for_minutes(to_park_time / 60)
    compassion_words = _words_for_minutes(park_time / 60)
    closing_words = _words_for_minutes(park_to_destination_time / 60)

    weather, source_to_park, park_to_destination = await asyncio.gather(
        asyncio.to_thread(current_weather_lookup, park.lat, park.lon),
        build_route_elevation_segments(
            source,
            park,
            selected_steps=list(source_to_park_steps or []),
        ),
        build_route_elevation_segments(
            park,
            destination,
            selected_steps=list(park_to_destination_steps or []),
        ),
    )
    weather_condition = _format_weather_condition(weather)
    duration_minutes = max(
        1,
        round((to_park_time + park_time + park_to_destination_time) / 60),
    )
    focused_phase = "source_to_park"
    closing_phase = "park_to_destination"
    focused_ids = [_segment_id(focused_phase, segment) for segment in source_to_park]
    closing_ids = [_segment_id(closing_phase, segment) for segment in park_to_destination]

    prompt = render_template(
        get_script_generation_prompt("WITH_PARK"),
        context=context,
        duration_minutes=duration_minutes,
        FAM_word_count=focused_words,
        source_to_park=_route_context(source_to_park, focused_phase),
        park_to_destination=_route_context(park_to_destination, closing_phase),
        CM_word_count=compassion_words,
        Closing_word_count=closing_words,
        weatherCondition=weather_condition,
        study_parameters=study_parameters.strip() or "No additional study instructions.",
    )
    generated = await generate_text(
        prompt,
        json_schema=_narration_schema(focused_ids, closing_ids),
        schema_name="meditation_narrations",
    )
    narrations = _parse_narrations(generated.text, focused_ids, closing_ids)
    script = _assemble_script(
        duration_minutes=duration_minutes,
        context=context,
        weather_condition=weather_condition,
        source=source,
        destination=destination,
        park=park,
        focused_words=focused_words,
        compassion_words=compassion_words,
        closing_words=closing_words,
        focused_segments=source_to_park,
        focused_route_phase=focused_phase,
        closing_segments=park_to_destination,
        closing_route_phase=closing_phase,
        narrations=narrations,
    )

    if save_to_disk:
        save_script_text(
            json.dumps(script.model_dump(mode="json"), ensure_ascii=False, indent=2),
            output_folder,
            prefix="meditation_script",
        )
    return GeneratedScript(script=script, usage=generated.usage)


async def generate_script_without_park(
    source: LatLng,
    destination: LatLng,
    total_walking_time: int,
    context: str,
    study_parameters: str = "",
    route_steps: Sequence[RouteStepInfo] | None = None,
    output_folder: Path = DEFAULT_OUTPUT_FOLDER,
    save_to_disk: bool = False,
) -> GeneratedScript:
    focused_words = _words_for_minutes(
        (total_walking_time * FOCUSED_ATTENTION_SHARE) / 60
    )
    compassion_words = _words_for_minutes(
        (total_walking_time * COMPASSION_SHARE) / 60
    )
    closing_words = _words_for_minutes((total_walking_time * CLOSING_SHARE) / 60)

    weather, route_segments = await asyncio.gather(
        asyncio.to_thread(current_weather_lookup, destination.lat, destination.lon),
        build_route_elevation_segments(
            source,
            destination,
            selected_steps=list(route_steps or []),
        ),
    )
    weather_condition = _format_weather_condition(weather)
    duration_minutes = max(1, round(total_walking_time / 60))
    focused_phase = "source_to_destination"
    focused_ids = [_segment_id(focused_phase, segment) for segment in route_segments]
    closing_ids: List[str] = []

    prompt = render_template(
        get_script_generation_prompt("WITHOUT_PARK"),
        context=context,
        duration_minutes=duration_minutes,
        FAM_word_count=focused_words,
        source_to_park=_route_context(route_segments, focused_phase),
        CM_word_count=compassion_words,
        Closing_word_count=closing_words,
        weatherCondition=weather_condition,
        study_parameters=study_parameters.strip() or "No additional study instructions.",
    )
    generated = await generate_text(
        prompt,
        json_schema=_narration_schema(focused_ids, closing_ids),
        schema_name="meditation_narrations",
    )
    narrations = _parse_narrations(generated.text, focused_ids, closing_ids)
    script = _assemble_script(
        duration_minutes=duration_minutes,
        context=context,
        weather_condition=weather_condition,
        source=source,
        destination=destination,
        park=None,
        focused_words=focused_words,
        compassion_words=compassion_words,
        closing_words=closing_words,
        focused_segments=route_segments,
        focused_route_phase=focused_phase,
        closing_segments=[],
        closing_route_phase="park_to_destination",
        narrations=narrations,
    )

    if save_to_disk:
        save_script_text(
            json.dumps(script.model_dump(mode="json"), ensure_ascii=False, indent=2),
            output_folder,
            prefix="meditation_script",
        )
    return GeneratedScript(script=script, usage=generated.usage)
