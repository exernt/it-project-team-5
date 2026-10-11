import unittest
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from route.clients.openai_client import (
    DEFAULT_MODEL,
    _calculate_luna_standard_cost_usd,
    _generate_text_sync,
)


class OpenAIClientTests(unittest.TestCase):
    @patch("route.clients.openai_client.OpenAI")
    def test_uses_luna_and_reports_generation_cost(self, openai_mock):
        response = SimpleNamespace(
            model="gpt-6-luna",
            service_tier="default",
            output_text="  generated script  ",
            usage=SimpleNamespace(
                input_tokens=1_000,
                input_tokens_details=SimpleNamespace(
                    cached_tokens=200,
                    cache_write_tokens=100,
                ),
                output_tokens=500,
                output_tokens_details=SimpleNamespace(reasoning_tokens=0),
                total_tokens=1_500,
            ),
        )
        client = MagicMock()
        client.responses.create.return_value = response
        openai_mock.return_value = client

        result = _generate_text_sync("prompt", api_key="test-key")

        self.assertEqual(DEFAULT_MODEL, "gpt-6-luna")
        self.assertEqual("generated script", result.text)
        self.assertEqual(0.0003345, result.usage.cost_usd)
        self.assertEqual(200, result.usage.cached_input_tokens)
        self.assertEqual(100, result.usage.cache_write_tokens)
        client.responses.create.assert_called_once_with(
            model="gpt-6-luna",
            reasoning={"effort": "none"},
            input="prompt",
            temperature=0.5,
            service_tier="default",
        )

    def test_applies_long_context_luna_rates(self):
        cost = _calculate_luna_standard_cost_usd(
            input_tokens=300_000,
            cached_input_tokens=0,
            cache_write_tokens=0,
            output_tokens=100_000,
        )

        self.assertEqual(0.135, cost)

    @patch("route.clients.openai_client.OpenAI")
    def test_rejects_models_without_configured_pricing(self, openai_mock):
        with self.assertRaisesRegex(ValueError, "configured only for gpt-6-luna"):
            _generate_text_sync(
                "prompt",
                model="gpt-4.1-mini",
                api_key="test-key",
            )

        openai_mock.assert_not_called()

    @patch("route.clients.openai_client.OpenAI")
    def test_sends_strict_json_schema_to_responses_api(self, openai_mock):
        response = SimpleNamespace(
            model="gpt-6-luna",
            service_tier="default",
            output_text='{"narration":"Hello"}',
            usage=SimpleNamespace(
                input_tokens=10,
                input_tokens_details=SimpleNamespace(
                    cached_tokens=0,
                    cache_write_tokens=0,
                ),
                output_tokens=5,
                output_tokens_details=SimpleNamespace(reasoning_tokens=0),
                total_tokens=15,
            ),
        )
        client = MagicMock()
        client.responses.create.return_value = response
        openai_mock.return_value = client
        schema = {
            "type": "object",
            "properties": {"narration": {"type": "string"}},
            "required": ["narration"],
            "additionalProperties": False,
        }

        _generate_text_sync(
            "prompt",
            api_key="test-key",
            json_schema=schema,
            schema_name="meditation_narrations",
        )

        request = client.responses.create.call_args.kwargs
        self.assertEqual(
            {
                "format": {
                    "type": "json_schema",
                    "name": "meditation_narrations",
                    "schema": schema,
                    "strict": True,
                }
            },
            request["text"],
        )


if __name__ == "__main__":
    unittest.main()
