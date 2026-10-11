from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import Any, Dict, Optional

from openai import OpenAI

from route.config import openai_api_key

logger = logging.getLogger(__name__)

DEFAULT_MODEL = "gpt-6-luna"
DEFAULT_TEMPERATURE = 0.5

# GPT-6 Luna Standard rates in USD per one million tokens.
_SHORT_CONTEXT_INPUT_RATE = 0.10
_SHORT_CONTEXT_CACHED_INPUT_RATE = 0.01
_SHORT_CONTEXT_CACHE_WRITE_RATE = 0.125
_SHORT_CONTEXT_OUTPUT_RATE = 0.50
_LONG_CONTEXT_INPUT_RATE = 0.20
_LONG_CONTEXT_CACHED_INPUT_RATE = 0.02
_LONG_CONTEXT_CACHE_WRITE_RATE = 0.25
_LONG_CONTEXT_OUTPUT_RATE = 0.75
_LONG_CONTEXT_THRESHOLD = 272_000
_TOKENS_PER_MILLION = 1_000_000


@dataclass(frozen=True)
class GenerationUsage:
    model: str
    service_tier: str
    input_tokens: int
    cached_input_tokens: int
    cache_write_tokens: int
    output_tokens: int
    reasoning_tokens: int
    total_tokens: int
    cost_usd: float


@dataclass(frozen=True)
class GeneratedText:
    text: str
    usage: GenerationUsage


def _token_count(value: object, attribute: str) -> int:
    return int(getattr(value, attribute, 0) or 0)


def _calculate_luna_standard_cost_usd(
    *,
    input_tokens: int,
    cached_input_tokens: int,
    cache_write_tokens: int,
    output_tokens: int,
) -> float:
    if input_tokens > _LONG_CONTEXT_THRESHOLD:
        input_rate = _LONG_CONTEXT_INPUT_RATE
        cached_input_rate = _LONG_CONTEXT_CACHED_INPUT_RATE
        cache_write_rate = _LONG_CONTEXT_CACHE_WRITE_RATE
        output_rate = _LONG_CONTEXT_OUTPUT_RATE
    else:
        input_rate = _SHORT_CONTEXT_INPUT_RATE
        cached_input_rate = _SHORT_CONTEXT_CACHED_INPUT_RATE
        cache_write_rate = _SHORT_CONTEXT_CACHE_WRITE_RATE
        output_rate = _SHORT_CONTEXT_OUTPUT_RATE

    ordinary_input_tokens = max(
        0,
        input_tokens - cached_input_tokens - cache_write_tokens,
    )
    cost = (
        ordinary_input_tokens * input_rate
        + cached_input_tokens * cached_input_rate
        + cache_write_tokens * cache_write_rate
        + output_tokens * output_rate
    ) / _TOKENS_PER_MILLION
    return round(cost, 10)


def _generate_text_sync(
    prompt: str,
    *,
    model: str = DEFAULT_MODEL,
    temperature: float = DEFAULT_TEMPERATURE,
    api_key: Optional[str] = None,
    json_schema: Optional[Dict[str, Any]] = None,
    schema_name: str = "structured_response",
) -> GeneratedText:
    if model != DEFAULT_MODEL:
        raise ValueError(
            f"Cost calculation is configured only for {DEFAULT_MODEL}; received {model}"
        )

    client = OpenAI(api_key=api_key or openai_api_key())
    request: Dict[str, Any] = dict(
        model=model,
        reasoning={"effort": "none"},
        input=prompt,
        temperature=temperature,
        service_tier="default",
    )
    if json_schema is not None:
        request["text"] = {
            "format": {
                "type": "json_schema",
                "name": schema_name,
                "schema": json_schema,
                "strict": True,
            }
        }

    resp = client.responses.create(**request)

    if resp.usage is None:
        raise RuntimeError("OpenAI response did not include token usage")

    input_details = resp.usage.input_tokens_details
    output_details = resp.usage.output_tokens_details
    input_tokens = _token_count(resp.usage, "input_tokens")
    cached_input_tokens = _token_count(input_details, "cached_tokens")
    cache_write_tokens = _token_count(input_details, "cache_write_tokens")
    output_tokens = _token_count(resp.usage, "output_tokens")
    reasoning_tokens = _token_count(output_details, "reasoning_tokens")
    total_tokens = _token_count(resp.usage, "total_tokens")
    cost_usd = _calculate_luna_standard_cost_usd(
        input_tokens=input_tokens,
        cached_input_tokens=cached_input_tokens,
        cache_write_tokens=cache_write_tokens,
        output_tokens=output_tokens,
    )
    usage = GenerationUsage(
        model=str(getattr(resp, "model", None) or model),
        service_tier=str(getattr(resp, "service_tier", None) or "default"),
        input_tokens=input_tokens,
        cached_input_tokens=cached_input_tokens,
        cache_write_tokens=cache_write_tokens,
        output_tokens=output_tokens,
        reasoning_tokens=reasoning_tokens,
        total_tokens=total_tokens or input_tokens + output_tokens,
        cost_usd=cost_usd,
    )
    logger.info(
        "OpenAI script generation completed model=%s service_tier=%s "
        "input_tokens=%d cached_input_tokens=%d cache_write_tokens=%d "
        "output_tokens=%d reasoning_tokens=%d total_tokens=%d cost_usd=%.10f",
        usage.model,
        usage.service_tier,
        usage.input_tokens,
        usage.cached_input_tokens,
        usage.cache_write_tokens,
        usage.output_tokens,
        usage.reasoning_tokens,
        usage.total_tokens,
        usage.cost_usd,
    )
    return GeneratedText(
        text=(resp.output_text or "").strip(),
        usage=usage,
    )


async def generate_text(
    prompt: str,
    *,
    model: str = DEFAULT_MODEL,
    temperature: float = DEFAULT_TEMPERATURE,
    api_key: Optional[str] = None,
    json_schema: Optional[Dict[str, Any]] = None,
    schema_name: str = "structured_response",
) -> GeneratedText:
    """Run the synchronous OpenAI SDK call without blocking the event loop."""
    return await asyncio.to_thread(
        _generate_text_sync,
        prompt,
        model=model,
        temperature=temperature,
        api_key=api_key,
        json_schema=json_schema,
        schema_name=schema_name,
    )
