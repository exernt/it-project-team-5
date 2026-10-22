from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Sequence

import httpx

from route.clients.google_nearby import search_nearby_features
from route.clients.google_weather import current_weather_lookup
from route.clients.openai_client import GenerationUsage, generate_text
from route.config import (
    PROMPT_VERSION,
    get_script_generation_prompt,
    nearby_context_enabled,
    script_fallback_enabled,
    render_template,
)
from route.errors import OpenaiAPIError
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
from route.services.script_fallback import FallbackSegment, build_fallback_narrations
from route.utils.route_hint import extract_street_hint

logger = logging.getLogger(__name__)

# A section may fall this far below its requested minimum before we log a warning.
WORD_COUNT_TOLERANCE = 0.8
MAX_GENERATION_ATTEMPTS = 2
NEARBY_LOOKUP_CONCURRENCY = 5
NEARBY_MAX_SEGMENTS = 20

FOCUSED_ATTENTION_SHARE = 0.2
COMPASSION_SHARE = 0.4
CLOSING_SHARE = 0.4

BASE_DIR = Path(__file__).resolve().parents[1]  # route/
DEFAULT_OUTPUT_FOLDER = BASE_DIR / "scripts"


@dataclass(frozen=True)
class GeneratedScript:
    script: MeditationScript
    usage: GenerationUsage


def _words_for_minutes(minutes: float, *, wpm: int = 110) -> int:
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


def _route_context(
        segments: Sequence[StepSegment],
        route_phase: str,
        nearby: Dict[str, List[str]] | None = None,
) -> str:
    nearby = nearby or {}
    items = []
    for segment in segments:
        segment_id = _segment_id(route_phase, segment)
        item: Dict[str, Any] = {
            "segment_id": segment_id,
            "route_label": extract_street_hint(segment.instruction),
            "instruction": segment.instruction,
            "terrain": segment.label,
        }
        if nearby.get(segment_id):
            item["nearby_features"] = nearby[segment_id]
        items.append(item)
    return json.dumps(items, ensure_ascii=False, indent=2)


def _segment_midpoint(segment: StepSegment) -> LatLng:
    return LatLng(
        lat=(segment.start.lat + segment.end.lat) / 2,
        lon=(segment.start.lon + segment.end.lon) / 2,
    )


async def _lookup_nearby_features(
        segments: Sequence[StepSegment],
        route_phase: str,
) -> Dict[str, List[str]]:
    """Best-effort landmark lookup per segment; any failure yields no context."""
    if not nearby_context_enabled() or not segments:
        return {}

    semaphore = asyncio.Semaphore(NEARBY_LOOKUP_CONCURRENCY)

    async def lookup(client: httpx.AsyncClient, segment: StepSegment):
        segment_id = _segment_id(route_phase, segment)
        async with semaphore:
            try:
                names = await search_nearby_features(client, _segment_midpoint(segment))
            except Exception:
                logger.warning(
                    "Nearby feature lookup failed for %s", segment_id, exc_info=True
                )
                return segment_id, []
        return segment_id, names

    async with httpx.AsyncClient(timeout=10.0) as client:
        results = await asyncio.gather(
            *(lookup(client, segment) for segment in segments[:NEARBY_MAX_SEGMENTS])
        )
    return {segment_id: names for segment_id, names in results if names}


def _word_count(text: str) -> int:
    return len(text.split())


def _word_count_shortfalls(
        narrations: Dict[str, Any],
        focused_words: int,
        compassion_words: int,
        closing_words: int,
) -> List[str]:
    """Describe sections whose generated text is well under the requested minimum."""
    focused = narrations["focused_attention"]
    closing = narrations["closing_meditation"]
    actual = {
        "focused_attention": (
            _word_count(focused["opening_narration"])
            + sum(_word_count(t) for t in focused["segment_narrations"].values())
            + _word_count(focused["closing_narration"]),
            focused_words,
        ),
        "compassion_meditation": (
            _word_count(narrations["compassion_meditation"]["narration"]),
            compassion_words,
        ),
        "closing_meditation": (
            _word_count(closing["opening_narration"])
            + sum(_word_count(t) for t in closing["segment_narrations"].values())
            + _word_count(closing["closing_narration"]),
            closing_words,
        ),
    }
    return [
        f"{name}: {words} words, requested at least {minimum}"
        for name, (words, minimum) in actual.items()
        if minimum > 0 and words < minimum * WORD_COUNT_TOLERANCE
    ]


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


def _zero_usage() -> GenerationUsage:
    return GenerationUsage(
        model="fallback",
        service_tier="none",
        input_tokens=0,
        cached_input_tokens=0,
        cache_write_tokens=0,
        output_tokens=0,
        reasoning_tokens=0,
        total_tokens=0,
        cost_usd=0.0,
    )


def _combine_usage(first: GenerationUsage, second: GenerationUsage) -> GenerationUsage:
    return GenerationUsage(
        model=second.model,
        service_tier=second.service_tier,
        input_tokens=first.input_tokens + second.input_tokens,
        cached_input_tokens=first.cached_input_tokens + second.cached_input_tokens,
        cache_write_tokens=first.cache_write_tokens + second.cache_write_tokens,
        output_tokens=first.output_tokens + second.output_tokens,
        reasoning_tokens=first.reasoning_tokens + second.reasoning_tokens,
        total_tokens=first.total_tokens + second.total_tokens,
        cost_usd=round(first.cost_usd + second.cost_usd, 10),
    )


def _retry_prompt(prompt: str, problems: Sequence[str]) -> str:
    listed = "\n".join(f"- {problem}" for problem in problems)
    return (
        f"{prompt}\n\nYour previous response had these problems. "
        f"Fix them in this response:\n{listed}\n"
        "Keep every other requirement unchanged."
    )


async def _generate_validated_narrations(
        prompt: str,
        focused_ids: Sequence[str],
        closing_ids: Sequence[str],
        focused_words: int,
        compassion_words: int,
        closing_words: int,
) -> tuple[Dict[str, Any] | None, GenerationUsage]:
    """Generate narration, retrying once on invalid or too-short output.

    Returns ``(None, usage)`` when OpenAI fails or the output stays structurally
    invalid, so the caller can use the fallback script. Output that is valid but
    still short after the last attempt is accepted with a warning.
    """
    usage: GenerationUsage | None = None
    current_prompt = prompt
    for attempt in range(1, MAX_GENERATION_ATTEMPTS + 1):
        try:
            generated = await generate_text(
                current_prompt,
                json_schema=_narration_schema(focused_ids, closing_ids),
                schema_name="meditation_narrations",
            )
        except OpenaiAPIError:
            logger.error(
                "OpenAI unavailable on attempt %d",
                attempt,
                exc_info=True,
            )
            return None, usage or _zero_usage()

        usage = generated.usage if usage is None else _combine_usage(usage, generated.usage)
        narrations: Dict[str, Any] | None
        try:
            narrations = _parse_narrations(generated.text, focused_ids, closing_ids)
        except ValueError as exc:
            narrations = None
            problems = [str(exc)]
        else:
            problems = _word_count_shortfalls(
                narrations, focused_words, compassion_words, closing_words
            )
            if not problems:
                return narrations, usage

        if attempt == MAX_GENERATION_ATTEMPTS:
            if narrations is None:
                logger.error(
                    "Generated narration still invalid after %d attempts: %s",
                    attempt,
                    problems[0],
                )
                return None, usage
            for problem in problems:
                logger.warning("Generated script under word target - %s", problem)
            return narrations, usage

        logger.warning("Retrying script generation (attempt %d): %s", attempt, problems)
        current_prompt = _retry_prompt(prompt, problems)

    raise AssertionError("unreachable")  # pragma: no cover


def _fallback_segments(
        segments: Sequence[StepSegment], route_phase: str
) -> List[FallbackSegment]:
    return [
        (
            _segment_id(route_phase, segment),
            extract_street_hint(segment.instruction),
            segment.label,
        )
        for segment in segments
    ]


def _attach_route_metadata(
        segments: Sequence[StepSegment],
        route_phase: str,
        narrations: Dict[str, str],
        nearby: Dict[str, List[str]] | None = None,
) -> List[ScriptRouteSegment]:
    nearby = nearby or {}
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
            nearby_features=nearby.get(_segment_id(route_phase, segment), []),
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
        nearby: Dict[str, List[str]] | None = None,
        model: str = "",
        fallback: bool = False,
) -> MeditationScript:
    focused = narrations["focused_attention"]
    compassion = narrations["compassion_meditation"]
    closing = narrations["closing_meditation"]

    return MeditationScript(
        duration_minutes=duration_minutes,
        language="simple",
        includes_pauses=True,
        prompt_version=PROMPT_VERSION,
        model=model,
        fallback=fallback,
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
                    nearby,
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
                    nearby,
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
    focused_nearby, closing_nearby = await asyncio.gather(
        _lookup_nearby_features(source_to_park, focused_phase),
        _lookup_nearby_features(park_to_destination, closing_phase),
    )
    nearby = {**focused_nearby, **closing_nearby}

    prompt = render_template(
        get_script_generation_prompt("WITH_PARK"),
        context=context,
        duration_minutes=duration_minutes,
        FAM_word_count=focused_words,
        source_to_park=_route_context(source_to_park, focused_phase, nearby),
        park_to_destination=_route_context(park_to_destination, closing_phase, nearby),
        CM_word_count=compassion_words,
        Closing_word_count=closing_words,
        weatherCondition=weather_condition,
        study_parameters=study_parameters.strip() or "No additional study instructions.",
    )
    narrations, usage = await _generate_validated_narrations(
        prompt,
        focused_ids,
        closing_ids,
        focused_words,
        compassion_words,
        closing_words,
    )
    used_fallback = narrations is None
    if narrations is None:
        if not script_fallback_enabled():
            raise OpenaiAPIError("Script generation failed and fallback is disabled")
        narrations = build_fallback_narrations(
            _fallback_segments(source_to_park, focused_phase),
            _fallback_segments(park_to_destination, closing_phase),
            has_park=True,
        )
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
        nearby=nearby,
        model=usage.model,
        fallback=used_fallback,
    )

    if save_to_disk:
        save_script_text(
            json.dumps(script.model_dump(mode="json"), ensure_ascii=False, indent=2),
            output_folder,
            prefix="meditation_script",
        )
    return GeneratedScript(script=script, usage=usage)


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
    nearby = await _lookup_nearby_features(route_segments, focused_phase)

    prompt = render_template(
        get_script_generation_prompt("WITHOUT_PARK"),
        context=context,
        duration_minutes=duration_minutes,
        FAM_word_count=focused_words,
        source_to_park=_route_context(route_segments, focused_phase, nearby),
        CM_word_count=compassion_words,
        Closing_word_count=closing_words,
        weatherCondition=weather_condition,
        study_parameters=study_parameters.strip() or "No additional study instructions.",
    )
    narrations, usage = await _generate_validated_narrations(
        prompt,
        focused_ids,
        closing_ids,
        focused_words,
        compassion_words,
        closing_words,
    )
    used_fallback = narrations is None
    if narrations is None:
        if not script_fallback_enabled():
            raise OpenaiAPIError("Script generation failed and fallback is disabled")
        narrations = build_fallback_narrations(
            _fallback_segments(route_segments, focused_phase),
            [],
            has_park=False,
        )
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
        nearby=nearby,
        model=usage.model,
        fallback=used_fallback,
    )

    if save_to_disk:
        save_script_text(
            json.dumps(script.model_dump(mode="json"), ensure_ascii=False, indent=2),
            output_folder,
            prefix="meditation_script",
        )
    return GeneratedScript(script=script, usage=usage)