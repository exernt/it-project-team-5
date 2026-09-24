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

    @abstractmethod
    def validate_answer(self, question: dict, scales: dict, value: Any) -> None:
        """Raise ValueError if `value` isn't a well-formed answer to this
        question -- i.e. matches the shape collect_answer would produce."""


class TextHandler(QuestionHandler):
    def validate_definition(self, question: dict, scales: dict) -> None:
        pass  # no extra fields required beyond id/type/prompt

    def collect_answer(self, question: dict, scales: dict) -> str:
        print(f"\n{question['prompt']}")
        return input("> ").strip()

    def validate_answer(self, question: dict, scales: dict, value: Any) -> None:
        if not isinstance(value, str):
            raise ValueError(f"{question['id']}: expected a string, got {type(value).__name__}")


def _validate_choice_value(question: dict, options: list, value: Any) -> None:
    """Shared validation for one selected option's answer shape, used by
    both SingleChoiceHandler and MultipleChoiceHandler. `value` must be a
    dict of the form {"value": ..., "detail": ...}, matching what
    collect_answer produces -- and if the selected option itself required
    free text, 'detail' must actually be a non-empty string."""
    if not isinstance(value, dict) or "value" not in value:
        raise ValueError(
            f"{question['id']}: expected {{'value': ..., 'detail': ...}}, got {value!r}"
        )

    valid_values = {
        (opt.get("value", opt.get("label")) if isinstance(opt, dict) else opt)
        for opt in options
    }
    if value["value"] not in valid_values:
        raise ValueError(
            f"{question['id']}: '{value['value']}' is not one of the valid options "
            f"{sorted(str(v) for v in valid_values)}"
        )

    matching_option = next(
        (
            opt for opt in options
            if isinstance(opt, dict) and opt.get("value", opt.get("label")) == value["value"]
        ),
        None,
    )
    if matching_option and matching_option.get("allow_free_text"):
        detail = value.get("detail")
        if not isinstance(detail, str) or not detail.strip():
            raise ValueError(
                f"{question['id']}: option '{value['value']}' requires non-empty 'detail' text"
            )


class SingleChoiceHandler(QuestionHandler):
    """Options may be plain strings, OR dicts of the form
    {"label": "...", "value": "...", "allow_free_text": true}.
    An option with allow_free_text prompts for additional detail text
    once selected (e.g. a classic "Other, please specify" choice) --
    without needing a separate question type for it."""

    def validate_definition(self, question: dict, scales: dict) -> None:
        _validate_options(question)

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

    def validate_answer(self, question: dict, scales: dict, value: Any) -> None:
        _validate_choice_value(question, question["options"], value)


class MultipleChoiceHandler(QuestionHandler):
    """Same structured-option support as SingleChoiceHandler, applied
    per selected option rather than to the question as a whole."""

    def validate_definition(self, question: dict, scales: dict) -> None:
        _validate_options(question)

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

    def validate_answer(self, question: dict, scales: dict, value: Any) -> None:
        if not isinstance(value, list):
            raise ValueError(f"{question['id']}: expected a list of selections, got {type(value).__name__}")
        for item in value:
            _validate_choice_value(question, question["options"], item)


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

        scale = scales[scale_ref]
        if not isinstance(scale, dict):
            raise ValueError(f"{question['id']}: referenced scale must be an object")
        if "options" in scale:
            options = scale["options"]
            if not isinstance(options, list) or not options:
                raise ValueError(f"{question['id']}: rating scale options must be a non-empty list")
            values: set[int] = set()
            for option in options:
                if not isinstance(option, dict) or "value" not in option or "label" not in option:
                    raise ValueError(
                        f"{question['id']}: each rating option requires 'value' and 'label'"
                    )
                value = option["value"]
                if not isinstance(value, int) or isinstance(value, bool):
                    raise ValueError(f"{question['id']}: rating option values must be integers")
                if not isinstance(option["label"], str) or not option["label"].strip():
                    raise ValueError(f"{question['id']}: rating option labels must be non-empty strings")
                if value in values:
                    raise ValueError(f"{question['id']}: duplicate rating option value {value}")
                values.add(value)
        else:
            minimum = scale.get("min")
            maximum = scale.get("max")
            if (
                not isinstance(minimum, int)
                or isinstance(minimum, bool)
                or not isinstance(maximum, int)
                or isinstance(maximum, bool)
                or minimum > maximum
            ):
                raise ValueError(
                    f"{question['id']}: rating scale requires integer 'min' and 'max' with min <= max"
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

    def validate_answer(self, question: dict, scales: dict, value: Any) -> None:
        if not isinstance(value, int) or isinstance(value, bool):
            raise ValueError(f"{question['id']}: expected an integer, got {type(value).__name__}")

        scale = scales[question["scale_ref"]]
        if "options" in scale:
            valid_values = {opt["value"] for opt in scale["options"]}
            if value not in valid_values:
                raise ValueError(
                    f"{question['id']}: {value} is not one of the scale's valid values "
                    f"{sorted(valid_values)}"
                )
        else:
            if not (scale["min"] <= value <= scale["max"]):
                raise ValueError(
                    f"{question['id']}: {value} is outside the scale's range "
                    f"[{scale['min']}, {scale['max']}]"
                )


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


def _validate_options(question: dict) -> None:
    options = question.get("options")
    if not isinstance(options, list) or not options:
        raise ValueError(f"{question['id']}: choice question requires a non-empty 'options' list")

    values: set[object] = set()
    for option in options:
        if isinstance(option, str):
            if not option.strip():
                raise ValueError(f"{question['id']}: option strings must not be empty")
            value: object = option
        elif isinstance(option, dict):
            label = option.get("label")
            if not isinstance(label, str) or not label.strip():
                raise ValueError(f"{question['id']}: structured option requires a non-empty 'label'")
            if "allow_free_text" in option and not isinstance(option["allow_free_text"], bool):
                raise ValueError(f"{question['id']}: 'allow_free_text' must be a boolean")
            value = option.get("value", label)
            if not isinstance(value, (str, int, float, bool)):
                raise ValueError(f"{question['id']}: option 'value' must be a JSON scalar")
        else:
            raise ValueError(f"{question['id']}: options must be strings or objects")

        if value in values:
            raise ValueError(f"{question['id']}: duplicate option value {value!r}")
        values.add(value)


def validate_questionnaire(questionnaire: dict) -> None:
    """Validate structure before use. Distinguishes two failure modes:
    - bad data (a type that isn't a recognised QuestionType at all)
    - an unimplemented feature (a recognised type with no handler yet)
    """
    if not isinstance(questionnaire, dict):
        raise ValueError("Questionnaire definition must be a JSON object")
    if "title" not in questionnaire or "questions" not in questionnaire:
        raise ValueError("Questionnaire missing 'title' or 'questions'")

    if not isinstance(questionnaire["title"], str) or not questionnaire["title"].strip():
        raise ValueError("Questionnaire 'title' must be a non-empty string")

    questions = questionnaire["questions"]
    if not isinstance(questions, list) or not questions:
        raise ValueError("Questionnaire 'questions' must be a non-empty list")

    scales = questionnaire.get("scales", {})
    if not isinstance(scales, dict):
        raise ValueError("Questionnaire 'scales' must be an object")

    question_ids: set[str] = set()
    for question in questions:
        if not isinstance(question, dict):
            raise ValueError("Each question must be a JSON object")
        if "id" not in question or "type" not in question:
            raise ValueError(f"Question missing 'id' or 'type': {question}")

        question_id = question["id"]
        if not isinstance(question_id, str) or not question_id.strip():
            raise ValueError("Question 'id' must be a non-empty string")
        if question_id in question_ids:
            raise ValueError(f"Duplicate question id: {question_id!r}")
        question_ids.add(question_id)

        prompt = question.get("prompt")
        if not isinstance(prompt, str) or not prompt.strip():
            raise ValueError(f"{question_id}: 'prompt' must be a non-empty string")
        if "required" in question and not isinstance(question["required"], bool):
            raise ValueError(f"{question_id}: 'required' must be a boolean")

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


def validate_response(questionnaire: dict, answers: dict) -> None:
    """Validate a submitted response's answers against the questionnaire's
    question definitions. Checks:
    - every answer's question id is actually part of this questionnaire
    - every question marked 'required' has a matching answer
    - each provided answer's shape/value is valid for its question type,
      via that type's own validate_answer (e.g. a rating stays within its
      scale's range, a choice answer's value is one of the real options)

    Does NOT assume validate_questionnaire() has already been run on
    `questionnaire` -- call both if the definition itself is untrusted too.
    """
    if not isinstance(answers, dict):
        raise ValueError(f"Response must be a dict of question_id -> answer, got {type(answers).__name__}")

    scales = questionnaire.get("scales", {})
    questions_by_id = {q["id"]: q for q in questionnaire["questions"]}

    unknown_ids = set(answers.keys()) - set(questions_by_id.keys())
    if unknown_ids:
        raise ValueError(f"Response references unknown question id(s): {sorted(unknown_ids)}")

    for question in questionnaire["questions"]:
        qid = question["id"]

        if qid not in answers:
            if question.get("required", False):
                raise ValueError(f"Missing required answer for question '{qid}'")
            continue

        handler = QUESTION_HANDLERS[QuestionType(question["type"])]
        handler.validate_answer(question, scales, answers[qid])


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
