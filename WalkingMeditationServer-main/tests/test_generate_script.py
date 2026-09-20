import unittest
from unittest.mock import AsyncMock, patch

from route.models import LatLng
from route.services.generate_script import generate_script_without_park


class StudyScriptParametersTests(unittest.IsolatedAsyncioTestCase):
    @patch("route.services.generate_script.generate_text", new_callable=AsyncMock)
    @patch("route.services.generate_script.source_to_dest_elevation", new_callable=AsyncMock)
    @patch("route.services.generate_script.current_weather_lookup")
    async def test_adds_researcher_parameters_to_generated_prompt(
        self,
        weather_mock,
        elevation_mock,
        generate_text_mock,
    ):
        weather_mock.return_value = {
            "weatherCondition": {"description": {"text": "Clear"}},
            "temperature": {"degrees": 20, "unit": "CELSIUS"},
        }
        elevation_mock.return_value = ["source", "destination"]
        generate_text_mock.return_value = (
            "<meditation><section><narration id='1'>Hello</narration></section></meditation>"
        )

        await generate_script_without_park(
            LatLng(lat=-37.81, lon=144.96),
            LatLng(lat=-37.80, lon=144.97),
            600,
            "general wellbeing",
            "Use short sentences and emphasize gratitude.",
        )

        prompt = generate_text_mock.await_args.args[0]
        self.assertIn("<study_instructions>", prompt)
        self.assertIn("Use short sentences and emphasize gratitude.", prompt)


if __name__ == "__main__":
    unittest.main()
