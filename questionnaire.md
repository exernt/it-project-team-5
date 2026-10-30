# Questionnaire format and API

Questionnaire definitions are reusable JSON documents owned by a researcher.
They are independent of studies: issuing a definition to a study creates a
separate, numbered questionnaire round, and participant answers belong to that
specific round.

## Definition JSON

A definition is a JSON object with these top-level fields:

| Field | Required | Format |
| --- | --- | --- |
| `title` | Yes | Non-empty string. This is also copied to the indexed database title column. |
| `questions` | Yes | Non-empty array of question objects. Question IDs must be unique. |
| `scales` | For rating questions | Object keyed by the names used in `scale_ref`. |
| `description` | No | Display metadata; stored without server-side interpretation. |
| `instructions` | No | Display metadata; stored without server-side interpretation. |
| `citation` | No | Display metadata; stored without server-side interpretation. |
| `scoring` | No | Questionnaire-specific metadata; stored without server-side interpretation. |

Every question requires a non-empty string `id`, a supported `type`, and a
non-empty string `prompt`. Set `required` to `true` when an answer must be
present. It defaults to `false`.

The four supported question types are described below.

### Text

```json
{
  "id": "comments",
  "type": "text",
  "prompt": "How did the walk feel?",
  "required": true
}
```

The answer is a string:

```json
"Calm and focused"
```

### Single choice

Options may be strings or structured objects. A structured option may provide
a machine-readable `value` and may request additional text.

```json
{
  "id": "meditated",
  "type": "single_choice",
  "prompt": "Did you complete the meditation?",
  "required": true,
  "options": [
    "Yes",
    "No",
    {
      "label": "Other",
      "value": "other",
      "allow_free_text": true
    }
  ]
}
```

The answer is always an object. `detail` must be a non-empty string when the
selected option has `allow_free_text: true`; otherwise it may be `null`.

```json
{
  "value": "other",
  "detail": "I completed only half"
}
```

### Multiple choice

Multiple-choice options use the same format as single-choice options:

```json
{
  "id": "barriers",
  "type": "multiple_choice",
  "prompt": "What affected today's walk?",
  "options": ["Weather", "Time", "Noise"]
}
```

The answer is an array of choice-answer objects:

```json
[
  { "value": "Weather", "detail": null },
  { "value": "Noise", "detail": null }
]
```

### Rating

A rating question refers to a named top-level scale:

```json
{
  "scales": {
    "frequency": {
      "min": 0,
      "max": 3,
      "options": [
        { "value": 0, "label": "Not at all" },
        { "value": 1, "label": "Sometimes" },
        { "value": 2, "label": "Often" },
        { "value": 3, "label": "Very often" }
      ]
    }
  },
  "questions": [
    {
      "id": "stress",
      "type": "rating",
      "prompt": "How often did you feel stressed?",
      "required": true,
      "scale_ref": "frequency"
    }
  ]
}
```

A scale can instead omit `options` and provide integer `min` and `max` values.
The answer is an integer matching one of the option values, or falling within
the inclusive `min`/`max` range.

## Complete creation example

`POST /questionnaires` expects the definition inside a `definition` property:

```json
{
  "definition": {
    "title": "Weekly check-in",
    "description": "A short weekly wellbeing questionnaire",
    "instructions": "Answer based on the last seven days.",
    "scales": {
      "wellbeing": {
        "min": 1,
        "max": 5
      }
    },
    "questions": [
      {
        "id": "wellbeing",
        "type": "rating",
        "prompt": "How would you rate your wellbeing?",
        "required": true,
        "scale_ref": "wellbeing"
      },
      {
        "id": "comments",
        "type": "text",
        "prompt": "Is there anything else you would like to add?"
      }
    ]
  }
}
```

Invalid definitions receive HTTP 422. New definitions have `draft` status.
Only verified researchers can use definition-management endpoints, and list or
retrieval results are restricted to definitions owned by the current
researcher.

The owner may replace a draft's complete definition with
`PUT /questionnaires/{questionnaire_id}` using the same request body as
creation. This is full replacement rather than a partial merge. The response
contains the complete canonical questionnaire stored by the server so the
caller can confirm the accepted definition. Published and archived
questionnaires are immutable; editing either returns HTTP 409.

## Lifecycle and participant flow

1. A verified researcher creates a draft with `POST /questionnaires`.
2. While it remains a draft, the owner may replace its definition with
   `PUT /questionnaires/{questionnaire_id}`.
3. The owner publishes the validated definition with
   `POST /questionnaires/{questionnaire_id}/publish`.
4. The owner of an active study creates a new round with
   `POST /studies/{study_id}/questionnaires` and body
   `{"questionnaire_id": "<uuid>"}`.
5. `GET /studies/mine` includes each study's unrevoked rounds in
   `open_questionnaires`. Each summary contains the issuance `id`, definition
   `questionnaire_id`, `round_number`, `title`, timestamps, and the current
   participant's `has_responded` flag.
6. An active participant can refresh the same information with
   `GET /studies/{study_id}/questionnaires` and fetch displayable content with
   `GET /studies/{study_id}/questionnaires/{issuance_id}`.
7. The participant submits once with
   `POST /studies/{study_id}/questionnaires/{issuance_id}/responses`.
8. The study owner may close that round with
   `POST /studies/{study_id}/questionnaires/{issuance_id}/revoke` and retrieve
   its answers with
   `GET /studies/{study_id}/questionnaires/{issuance_id}/responses`.

Each issue call creates a new `round_number` for the same study/questionnaire
pair. The database natural key is `(study_id, questionnaire_id, round_number)`;
the issuance UUID is used in API paths. A participant may submit at most once
per issuance round, enforced both by the API and a database unique constraint.

## Submission format

The request contains one `answers` object keyed by question ID:

```json
{
  "answers": {
    "wellbeing": 4,
    "comments": "The guided section was helpful"
  }
}
```

Unknown question IDs, missing required answers, invalid option values, and
answers of the wrong type receive HTTP 422.
