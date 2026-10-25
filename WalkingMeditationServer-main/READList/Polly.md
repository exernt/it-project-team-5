# Amazon Polly TTS

`POST /tts` now accepts the AWS-shaped request produced by Android's `PollyPayload`:

```json
{
  "Text": "<speak>Notice your breathing.<break time=\"2500ms\"/>Keep walking.</speak>",
  "TextType": "ssml",
  "VoiceId": "Joanna",
  "LanguageCode": "en-US",
  "Engine": "standard",
  "OutputFormat": "mp3"
}
```

It returns raw MP3 bytes with `Content-Type: audio/mpeg`. Requests require the signed-in user's `Authorization: Bearer <access_token>` header. Android attaches the token and attempts one refresh after a 401 through `AuthenticatedHttpClient`; shared app tokens are not used. Old Qwen payloads are rejected; update Android and the server together.

## Setup

1. Install `requirements.txt` (or `requirements-test.txt` for tests). TTS no longer loads Qwen, Torch, or soundfile. Install ffmpeg with the libmp3lame encoder for oversized narration; short requests do not require it. Optionally set FFMPEG_BINARY to its absolute path.
2. Set `AWS_REGION` (for example `ap-southeast-2`) or configure `AWS_DEFAULT_REGION` / your AWS profile's region.
3. Provide server-side credentials using the standard boto3 chain: an AWS profile for local development or a workload IAM role for deployment. Temporary environment credentials are also supported. Never put AWS credentials in Android or source control.
4. Give that identity permission for `polly:SynthesizeSpeech` on `*`. No S3 permissions are needed.
5. Use a voice/engine supported in the selected region. Android currently selects Joanna / en-US / standard. Voice and engine remain configurable in `PollyPayload`.

The SDK client is initialized lazily inside the worker thread. Route generation and server startup do not contact AWS. Connect/read timeouts and bounded retries are configured. The audio stream and client are closed on success or failure. Audio stays in memory and is returned directly; no local audio files or S3 objects are created, including during joining.

## Limits and errors

The app/backend accept narration blocks up to 60,000 total characters, including SSML. The backend splits oversized blocks into Polly requests targeting 2800 spoken / 5600 total characters (below the 3000 / 6000 service limits). Paragraph and sentence boundaries are preferred, then words, with character splitting only for unusually long words. XML is parsed before splitting, entities remain escaped, pauses stay in order, and enclosing SSML is reopened in each chunk. Pronunciation/substitution elements stay indivisible; an element that cannot fit is rejected. A block may contain at most 10 synthesis chunks. SSML must be well formed with a `speak` root; DTD/entity declarations and empty speech are rejected. Only MP3 output is accepted. Short requests still return Polly MP3 directly. Multi-chunk requests use 16 kHz mono PCM, join samples in order, and encode once to MP3 using ffmpeg pipes. The encoder is checked before AWS calls; missing ffmpeg returns 503. A chunk failure returns an error rather than partial audio. Retrying a failed block can repeat AWS synthesis for earlier chunks; completed blocks remain cached on Android.

Invalid input or provider-rejected speech settings return 422. AWS credential/configuration failures, throttling, and service failures return 503. Unexpected audio failures return 502. Responses omit AWS internal details. Validation responses may include the submitted input, so avoid logging full request/response bodies.

Tests mock the Polly client and block real AWS HTTP requests globally. They do not require credentials or incur AWS usage. `requirements-test.txt` includes imageio-ffmpeg so the default suite and CI require a real local encoding/decoding check; it is not silently skipped:

```shell
python -m pytest
```

References: [boto3 synthesis](https://docs.aws.amazon.com/boto3/latest/reference/services/polly/client/synthesize_speech.html), [credential configuration](https://docs.aws.amazon.com/boto3/latest/guide/credentials.html).

## Post-AWS acceptance checks (deferred until migration)

These checks require the deployed AWS environment and do not block creating this implementation PR:

- Confirm the workload IAM role grants `polly:SynthesizeSpeech`, and the region supports the configured voice/engine.
- Confirm authenticated `/tts` requests succeed over HTTPS, including token refresh; rejected requests must not invoke Polly.
- Confirm ffmpeg/libmp3lame is available for oversized blocks (or set FFMPEG_BINARY), and the deployment's HTTP timeouts accommodate chunked preparation.
- Listen to short and multi-chunk narration for pronunciation, pause duration, transitions, and final duration at the target 110 WPM word budget.
- Complete a phone walk after disconnecting the network: check route triggers, arrival narration, pause/resume, and automatic audio deletion.
- Exercise interrupted preparation, retry, missing files, early discard, and logout cleanup.

Credentials and voice settings are deployment configuration; AWS calls remain blocked in unit tests. Replaying prepared audio makes no synthesis requests. A failed oversized-block retry can repeat earlier chunk synthesis, so keep live smoke tests small.
