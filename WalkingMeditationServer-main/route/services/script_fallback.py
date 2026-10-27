"""Plain fallback narration used when OpenAI generation fails or stays invalid.

The text is fixed, safe to hear while walking, and built only from the route
segments, so it never depends on the OpenAI API or on user-provided free text.
"""
from __future__ import annotations

from typing import Any, Dict, Sequence, Tuple

# (segment_id, route_label, terrain_label) for each route segment.
FallbackSegment = Tuple[str, str, str]


def _terrain_sentence(terrain_label: str) -> str:
    label = terrain_label.casefold()
    if "uphill" in label:
        return "The path rises here, so let your pace ease and your breathing stay natural."
    if "downhill" in label:
        return "The path slopes down here, so keep your steps light and unhurried."
    return "The path is mostly flat here, so settle into an even, easy rhythm."


def _segment_text(route_label: str, terrain_label: str) -> str:
    where = route_label.strip() or "the path"
    return (
        f"Continue along {where}. {_terrain_sentence(terrain_label)} "
        "Feel your feet meet the ground, one step and then the next. (pause) "
        "Stay aware of the people and crossings around you, and return your "
        "attention to the rhythm of walking."
    )


def _segment_texts(segments: Sequence[FallbackSegment]) -> Dict[str, str]:
    return {
        segment_id: _segment_text(route_label, terrain_label)
        for segment_id, route_label, terrain_label in segments
    }


def build_fallback_narrations(
        focused: Sequence[FallbackSegment],
        closing: Sequence[FallbackSegment],
        *,
        has_park: bool,
) -> Dict[str, Any]:
    """Return narrations in the same shape the OpenAI response is parsed into."""
    if has_park:
        compassion = (
            "You have arrived at the park. Pause here for a moment and let your "
            "body settle. (pause) Silently wish yourself well: may I be calm, "
            "may I be safe. (pause) Now bring to mind someone you care about "
            "and wish them the same. (pause) Let that kindness extend to the "
            "people around you, whoever they may be. (pause) When you are "
            "ready, begin walking again."
        )
        closing_opening = (
            "Begin walking again, carrying a little of that kindness with you. "
            "Keep an easy pace and stay aware of your surroundings. (pause)"
        )
    else:
        compassion = (
            "As you keep walking, silently wish yourself well: may I be calm, "
            "may I be safe. (pause) Now bring to mind someone you care about "
            "and wish them the same. (pause) Let that kindness extend to the "
            "people around you, whoever they may be. (pause)"
        )
        closing_opening = (
            "Let your walking stay steady and unhurried, carrying a little of "
            "that kindness with you. (pause)"
        )

    return {
        "focused_attention": {
            "opening_narration": (
                "Welcome to this walking meditation. Choose a pace that feels "
                "comfortable and safe, and keep your eyes softly forward. "
                "There is nothing to achieve, only the simple act of walking. "
                "(pause)"
            ),
            "segment_narrations": _segment_texts(focused),
            "closing_narration": (
                "Let your attention rest on the feeling of walking for a few "
                "more steps. (pause)"
            ),
        },
        "compassion_meditation": {"narration": compassion},
        "closing_meditation": {
            "opening_narration": closing_opening,
            "segment_narrations": _segment_texts(closing),
            "closing_narration": (
                "As you arrive, take one slow breath and notice how your body "
                "feels. Thank yourself for taking this time. (pause)"
            ),
        },
    }