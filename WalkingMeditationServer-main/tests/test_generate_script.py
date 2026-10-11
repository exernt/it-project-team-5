import json
import unittest
from unittest.mock import AsyncMock, patch

from route.clients.openai_client import GeneratedText, GenerationUsage
from route.models import LatLng, StepSegment
from route.services.generate_script import (
    _route_context,
    _word_count_shortfalls,
    generate_script_with_park,
    generate_script_without_park,
)


def _usage() -> GenerationUsage:
    return GenerationUsage(
        model="gpt-6-luna",
        service_tier="default",
        input_tokens=100,
        cached_input_tokens=0,
        cache_write_tokens=0,
        output_tokens=50,
        reasoning_tokens=0,
        total_tokens=150,
        cost_usd=0.000035,
    )


def _segment(index: int, instruction: str = "Turn right onto Test St") -> StepSegment:
    return StepSegment(
        i=index,
        instruction=instruction,
        start=LatLng(lat=-37.81, lon=144.96),
        end=LatLng(lat=-37.80, lon=144.97),
        step_polyline="encoded",
        elev_start_m=20.04,
        elev_end_m=29.96,
        elev_gain_m=9.92,
        label="high elevation (uphill)",
    )


def _generated_json(
        focused_segment_ids: list[str],
        closing_segment_ids: list[str] | None = None,
) -> str:
    closing_segment_ids = closing_segment_ids or []
    return json.dumps(
        {
            "focused_attention": {
                "opening_narration": "Focused opening.",
                "segment_narrations": {
                    segment_id: f"Narration for {segment_id}."
                    for segment_id in focused_segment_ids
                },
                "closing_narration": "Focused closing.",
            },
            "compassion_meditation": {"narration": "Compassion narration."},
            "closing_meditation": {
                "opening_narration": "Closing opening.",
                "segment_narrations": {
                    segment_id: f"Narration for {segment_id}."
                    for segment_id in closing_segment_ids
                },
                "closing_narration": "Closing ending.",
            },
        }
    )


class ScriptGenerationTests(unittest.IsolatedAsyncioTestCase):
    @patch("route.services.generate_script.generate_text", new_callable=AsyncMock)
    @patch(
        "route.services.generate_script.build_route_elevation_segments",
        new_callable=AsyncMock,
    )
    @patch("route.services.generate_script.current_weather_lookup")
    async def test_generates_only_narration_and_attaches_route_metadata(
            self,
            weather_mock,
            elevation_mock,
            generate_text_mock,
    ):
        weather_mock.return_value = {
            "weatherCondition": {"description": {"text": "Clear"}},
            "temperature": {"degrees": 20, "unit": "CELSIUS"},
        }
        elevation_mock.return_value = [_segment(1)]
        generation_usage = _usage()
        generate_text_mock.return_value = GeneratedText(
            text=_generated_json(["source_to_destination_1"]),
            usage=generation_usage,
        )

        result = await generate_script_without_park(
            LatLng(lat=-37.81, lon=144.96),
            LatLng(lat=-37.80, lon=144.97),
            600,
            "general wellbeing",
            "Use short sentences and emphasize gratitude.",
        )

        prompt = generate_text_mock.await_args.args[0]
        request_kwargs = generate_text_mock.await_args.kwargs
        self.assertIn("<study_instructions>", prompt)
        self.assertIn("Use short sentences and emphasize gratitude.", prompt)
        self.assertNotIn("<meditation", prompt)
        self.assertEqual("meditation_narrations", request_kwargs["schema_name"])
        schema = request_kwargs["json_schema"]
        focused_segment_schema = schema["properties"]["focused_attention"][
            "properties"
        ]["segment_narrations"]
        self.assertEqual(
            ["source_to_destination_1"],
            focused_segment_schema["required"],
        )

        script = result.script
        self.assertEqual(generation_usage, result.usage)
        self.assertEqual("Clear, 20°C", script.inputs.weather_condition)
        self.assertIsNone(script.inputs.park)
        self.assertEqual(3, len(script.sections))
        route_segment = script.sections[0].route_segments[0]
        self.assertEqual("source_to_destination_1", route_segment.id)
        self.assertEqual("Test St", route_segment.route_label)
        self.assertEqual("high elevation (uphill)", route_segment.elevation.classification)
        self.assertEqual(9.9, route_segment.elevation.gain_m)
        self.assertEqual(
            "Narration for source_to_destination_1.",
            route_segment.narration,
        )
        self.assertEqual([], script.sections[2].route_segments)

    @patch("route.services.generate_script.generate_text", new_callable=AsyncMock)
    @patch(
        "route.services.generate_script.build_route_elevation_segments",
        new_callable=AsyncMock,
    )
    @patch("route.services.generate_script.current_weather_lookup")
    async def test_attaches_both_route_legs_for_park_script(
            self,
            weather_mock,
            elevation_mock,
            generate_text_mock,
    ):
        weather_mock.return_value = {
            "weatherCondition": {"description": {"text": "Cloudy"}},
            "temperature": {"degrees": 18, "unit": "CELSIUS"},
        }
        elevation_mock.side_effect = [
            [_segment(1, "Walk along First St")],
            [_segment(1, "Turn left onto Second St")],
        ]
        generate_text_mock.return_value = GeneratedText(
            text=_generated_json(
                ["source_to_park_1"],
                ["park_to_destination_1"],
            ),
            usage=_usage(),
        )
        park = LatLng(lat=-37.805, lon=144.965)

        result = await generate_script_with_park(
            LatLng(lat=-37.81, lon=144.96),
            LatLng(lat=-37.80, lon=144.97),
            park,
            300,
            300,
            300,
            "mindfulness",
        )

        self.assertEqual(park, result.script.inputs.park)
        self.assertEqual(
            "source_to_park_1",
            result.script.sections[0].route_segments[0].id,
        )
        self.assertEqual(
            "park_to_destination_1",
            result.script.sections[2].route_segments[0].id,
        )


class NearbyContextTests(unittest.IsolatedAsyncioTestCase):
    @patch.dict("os.environ", {"ENABLE_NEARBY_CONTEXT": "true"})
    @patch("route.services.generate_script.search_nearby_features", new_callable=AsyncMock)
    @patch("route.services.generate_script.generate_text", new_callable=AsyncMock)
    @patch(
        "route.services.generate_script.build_route_elevation_segments",
        new_callable=AsyncMock,
    )
    @patch("route.services.generate_script.current_weather_lookup")
    async def test_nearby_features_reach_prompt_and_script(
            self,
            weather_mock,
            elevation_mock,
            generate_text_mock,
            nearby_mock,
    ):
        weather_mock.return_value = {}
        elevation_mock.return_value = [_segment(1)]
        nearby_mock.return_value = ["Fitzroy Gardens"]
        generate_text_mock.return_value = GeneratedText(
            text=_generated_json(["source_to_destination_1"]),
            usage=_usage(),
        )

        result = await generate_script_without_park(
            LatLng(lat=-37.81, lon=144.96),
            LatLng(lat=-37.80, lon=144.97),
            600,
            "general wellbeing",
        )

        self.assertIn("Fitzroy Gardens", generate_text_mock.await_args.args[0])
        segment = result.script.sections[0].route_segments[0]
        self.assertEqual(["Fitzroy Gardens"], segment.nearby_features)
        self.assertEqual("gpt-6-luna", result.script.model)
        self.assertTrue(result.script.prompt_version)

    @patch.dict("os.environ", {"ENABLE_NEARBY_CONTEXT": "true"})
    @patch("route.services.generate_script.search_nearby_features", new_callable=AsyncMock)
    @patch("route.services.generate_script.generate_text", new_callable=AsyncMock)
    @patch(
        "route.services.generate_script.build_route_elevation_segments",
        new_callable=AsyncMock,
    )
    @patch("route.services.generate_script.current_weather_lookup")
    async def test_nearby_lookup_failure_does_not_block_generation(
            self,
            weather_mock,
            elevation_mock,
            generate_text_mock,
            nearby_mock,
    ):
        weather_mock.return_value = {}
        elevation_mock.return_value = [_segment(1)]
        nearby_mock.side_effect = RuntimeError("places down")
        generate_text_mock.return_value = GeneratedText(
            text=_generated_json(["source_to_destination_1"]),
            usage=_usage(),
        )

        result = await generate_script_without_park(
            LatLng(lat=-37.81, lon=144.96),
            LatLng(lat=-37.80, lon=144.97),
            600,
            "general wellbeing",
        )

        self.assertEqual([], result.script.sections[0].route_segments[0].nearby_features)

    @patch.dict("os.environ", {"ENABLE_NEARBY_CONTEXT": "false"})
    @patch("route.services.generate_script.search_nearby_features", new_callable=AsyncMock)
    @patch("route.services.generate_script.generate_text", new_callable=AsyncMock)
    @patch(
        "route.services.generate_script.build_route_elevation_segments",
        new_callable=AsyncMock,
    )
    @patch("route.services.generate_script.current_weather_lookup")
    async def test_nearby_lookup_is_off_by_default(
            self,
            weather_mock,
            elevation_mock,
            generate_text_mock,
            nearby_mock,
    ):
        weather_mock.return_value = {}
        elevation_mock.return_value = [_segment(1)]
        generate_text_mock.return_value = GeneratedText(
            text=_generated_json(["source_to_destination_1"]),
            usage=_usage(),
        )

        await generate_script_without_park(
            LatLng(lat=-37.81, lon=144.96),
            LatLng(lat=-37.80, lon=144.97),
            600,
            "general wellbeing",
        )

        nearby_mock.assert_not_called()


class RouteContextAndValidationTests(unittest.TestCase):
    def test_route_context_omits_nearby_features_when_none_found(self):
        context = json.loads(_route_context([_segment(1)], "source_to_park"))
        self.assertNotIn("nearby_features", context[0])

    def test_route_context_includes_nearby_features(self):
        context = json.loads(
            _route_context(
                [_segment(1)],
                "source_to_park",
                {"source_to_park_1": ["Royal Park"]},
            )
        )
        self.assertEqual(["Royal Park"], context[0]["nearby_features"])

    def test_word_count_shortfalls_flags_only_short_sections(self):
        long_text = " ".join(["word"] * 100)
        narrations = json.loads(_generated_json(["a"]))
        narrations["focused_attention"]["opening_narration"] = long_text
        narrations["closing_meditation"]["opening_narration"] = long_text

        shortfalls = _word_count_shortfalls(narrations, 50, 50, 50)

        self.assertEqual(1, len(shortfalls))
        self.assertIn("compassion_meditation", shortfalls[0])

    def test_word_count_shortfalls_ignores_zero_minimums(self):
        narrations = json.loads(_generated_json(["a"]))
        self.assertEqual([], _word_count_shortfalls(narrations, 0, 0, 0))


if __name__ == "__main__":
    unittest.main()
