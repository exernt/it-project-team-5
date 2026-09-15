"""
Load a questionnaire from JSON, collect responses via CLI, save the
responses to a JSON file, and demonstrate accessing the data.

Question types are handled through a registry (QUESTION_HANDLERS) rather
than an if/elif chain. Adding a new question type means writing one new
QuestionHandler subclass and registering it -- no changes needed to the
core loading, validation, or response-collection code.
"""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any


class QuestionType(str, Enum):
    """Source of truth for valid question types.

    Used to validate incoming JSON (catch typos / unsupported types)
    independently of whether a handler has actually been implemented
    for that type yet -- see validate_questionnaire().
    """

    TEXT = "text"
    SINGLE_CHOICE = "single_choice"
    MULTIPLE_CHOICE = "multiple_choice"
    RATING = "rating"


# ---------------------------------------------------------------------------
# Question handlers
# ---------------------------------------------------------------------------

class QuestionHandler(ABC):
    """Base interface every question type must implement."""

    @abstractmethod
    def validate_definition(self, question: dict, scales: dict) -> None:
        """Raise ValueError if this question's JSON is malformed for its type."""

    @abstractmethod
    def collect_answer(self, question: dict, scales: dict) -> Any:
        """Prompt for and return an answer. Swap this out for a web form,
        API handler, etc. without touching anything else in this file."""


class TextHandler(QuestionHandler):
    def validate_definition(self, question: dict, scales: dict) -> None:
        pass  # no extra fields required beyond id/type/prompt

    def collect_answer(self, question: dict, scales: dict) -> str:
        print(f"\n{question['prompt']}")
        return input("> ").strip()


class SingleChoiceHandler(QuestionHandler):
    """Options may be plain strings, OR dicts of the form
    {"label": "...", "value": "...", "allow_free_text": true}.
    An option with allow_free_text prompts for additional detail text
    once selected (e.g. a classic "Other, please specify" choice) --
    without needing a separate question type for it."""

    def validate_definition(self, question: dict, scales: dict) -> None:
        if "options" not in question:
            raise ValueError(f"{question['id']}: single_choice requires 'options'")
        for option in question["options"]:
            if isinstance(option, dict) and "label" not in option:
                raise ValueError(
                    f"{question['id']}: structured option missing 'label': {option}"
                )

    def collect_answer(self, question: dict, scales: dict) -> dict:
        print(f"\n{question['prompt']}")
        options = question["options"]
        for i, option in enumerate(options, start=1):
            label = option["label"] if isinstance(option, dict) else option
            print(f"  {i}. {label}")

        choice = int(input("> ")) - 1
        selected = options[choice]

        if isinstance(selected, dict):
            answer = {"value": selected.get("value", selected["label"]), "detail": None}
            if selected.get("allow_free_text"):
                answer["detail"] = input("  Please specify: ").strip()
            return answer

        return {"value": selected, "detail": None}


class MultipleChoiceHandler(QuestionHandler):
    """Same structured-option support as SingleChoiceHandler, applied
    per selected option rather than to the question as a whole."""

    def validate_definition(self, question: dict, scales: dict) -> None:
        if "options" not in question:
            raise ValueError(f"{question['id']}: multiple_choice requires 'options'")
        for option in question["options"]:
            if isinstance(option, dict) and "label" not in option:
                raise ValueError(
                    f"{question['id']}: structured option missing 'label': {option}"
                )

    def collect_answer(self, question: dict, scales: dict) -> list[dict]:
        print(f"\n{question['prompt']}")
        options = question["options"]
        for i, option in enumerate(options, start=1):
            label = option["label"] if isinstance(option, dict) else option
            print(f"  {i}. {label}")

        raw = input("> (comma-separated numbers) ")
        indices = [int(x.strip()) - 1 for x in raw.split(",") if x.strip()]

        answers = []
        for i in indices:
            selected = options[i]
            if isinstance(selected, dict):
                answer = {"value": selected.get("value", selected["label"]), "detail": None}
                if selected.get("allow_free_text"):
                    answer["detail"] = input(
                        f"  Please specify for '{selected['label']}': "
                    ).strip()
                answers.append(answer)
            else:
                answers.append({"value": selected, "detail": None})
        return answers


class RatingHandler(QuestionHandler):
    """Rating questions point at a named scale in the questionnaire's
    top-level 'scales' dict via 'scale_ref', rather than each question
    hardcoding its own min/max/options. This lets many questions share
    one scale definition (e.g. every DASS-21 item reuses 'dass_scale')."""

    def validate_definition(self, question: dict, scales: dict) -> None:
        scale_ref = question.get("scale_ref")
        if not scale_ref:
            raise ValueError(f"{question['id']}: rating requires 'scale_ref'")
        if scale_ref not in scales:
            raise ValueError(
                f"{question['id']}: scale_ref '{scale_ref}' not found in 'scales'"
            )

    def collect_answer(self, question: dict, scales: dict) -> int:
        scale = scales[question["scale_ref"]]
        print(f"\n{question['prompt']}")
        if "options" in scale:
            for opt in scale["options"]:
                print(f"  {opt['value']}: {opt['label']}")
        else:
            print(f"  (enter a number between {scale['min']} and {scale['max']})")
        return int(input("> "))


# The registry -- this is the only place new question types get "plugged in".
QUESTION_HANDLERS: dict[str, QuestionHandler] = {
    QuestionType.TEXT: TextHandler(),
    QuestionType.SINGLE_CHOICE: SingleChoiceHandler(),
    QuestionType.MULTIPLE_CHOICE: MultipleChoiceHandler(),
    QuestionType.RATING: RatingHandler(),
}


# ---------------------------------------------------------------------------
# Loading, validation, and response collection
# ---------------------------------------------------------------------------

def load_questionnaire(path: str) -> dict:
    """Load the questionnaire definition from a JSON file."""
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def validate_questionnaire(questionnaire: dict) -> None:
    """Validate structure before use. Distinguishes two failure modes:
    - bad data (a type that isn't a recognised QuestionType at all)
    - an unimplemented feature (a recognised type with no handler yet)
    """
    if "title" not in questionnaire or "questions" not in questionnaire:
        raise ValueError("Questionnaire missing 'title' or 'questions'")

    scales = questionnaire.get("scales", {})

    for question in questionnaire["questions"]:
        if "id" not in question or "type" not in question:
            raise ValueError(f"Question missing 'id' or 'type': {question}")

        try:
            q_type = QuestionType(question["type"])
        except ValueError:
            raise ValueError(f"Invalid question type: {question['type']!r}")

        handler = QUESTION_HANDLERS.get(q_type)
        if handler is None:
            raise ValueError(
                f"'{q_type}' is a recognised type but has no handler registered"
            )

        handler.validate_definition(question, scales)


def ask_question(question: dict, scales: dict):
    """Dispatch to the right handler. This function never changes when a
    new question type is added -- only QUESTION_HANDLERS does."""
    handler = QUESTION_HANDLERS[QuestionType(question["type"])]
    return handler.collect_answer(question, scales)


def collect_responses(questionnaire: dict) -> dict:
    """Walk through every question and build a responses dict."""
    validate_questionnaire(questionnaire)
    scales = questionnaire.get("scales", {})

    answers = {}
    for question in questionnaire["questions"]:
        answers[question["id"]] = ask_question(question, scales)

    return {
        "questionnaire_title": questionnaire["title"],
        "submitted_at": datetime.now().isoformat(),
        "answers": answers,
    }


# ---------------------------------------------------------------------------
# Persistence helpers
# ---------------------------------------------------------------------------

def save_responses(responses: dict, path: str):
    """Save a single response set to a JSON file."""
    with open(path, "w", encoding="utf-8") as f:
        json.dump(responses, f, indent=2)


def append_response(responses: dict, path: str):
    """Append a response set to a JSON file containing a list of responses."""
    file = Path(path)
    if file.exists():
        with open(file, "r", encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = []

    data.append(responses)

    with open(file, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)


def access_examples(responses: dict):
    """Show a few ways to pull data back out of the responses dict."""
    print("\n--- Accessing response data ---")
    print("Title:", responses["questionnaire_title"])
    print("Submitted at:", responses["submitted_at"])

    for question_id, answer in responses["answers"].items():
        print(f"  {question_id}: {answer}")


if __name__ == "__main__":
    questionnaire = load_questionnaire("questionnaire.json")
    responses = collect_responses(questionnaire)

    save_responses(responses, "response_single.json")
    append_response(responses, "responses_all.json")

    access_examples(responses)