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

It returns raw MP3 bytes with `Content-Type: audio/mpeg`. `X-App-Token` authentication is unchanged. Old Qwen payloads are rejected; update Android and the server together.

## Setup

1. Install `requirements.txt` (or `requirements-test.txt` for tests). TTS no longer loads Qwen, Torch, soundfile, or ffmpeg.
2. Set `AWS_REGION` (for example `ap-southeast-2`) or configure `AWS_DEFAULT_REGION` / your AWS profile's region.
3. Provide server-side credentials using the standard boto3 chain: an AWS profile for local development or a workload IAM role for deployment. Temporary environment credentials are also supported. Never put AWS credentials in Android or source control.
4. Give that identity permission for `polly:SynthesizeSpeech` on `*`. No S3 permissions are needed.
5. Use a voice/engine supported in the selected region. Android currently selects Joanna / en-US / standard. Voice and engine remain configurable in `PollyPayload`.

The SDK client is initialized lazily inside the worker thread. Route generation and server startup do not contact AWS. Connect/read timeouts and bounded retries are configured. The audio stream and client are closed on success or failure. Audio stays in memory and is returned directly; no local audio files or S3 objects are created.

## Limits and errors

Requests are limited to 3000 spoken characters and 6000 total input characters, including SSML. SSML must be well formed with a `speak` root; DTD/entity declarations and empty speech are rejected. Only MP3 output is accepted. Oversized-block splitting is not implemented yet.

Invalid input or provider-rejected speech settings return 422. AWS credential/configuration failures, throttling, and service failures return 503. Unexpected audio failures return 502. Responses omit AWS internal details. Validation responses may include the submitted input, so avoid logging full request/response bodies.

Tests mock the Polly client and do not require credentials or incur AWS usage:

```shell
python -m pytest
```

References: [boto3 synthesis](https://docs.aws.amazon.com/boto3/latest/reference/services/polly/client/synthesize_speech.html), [credential configuration](https://docs.aws.amazon.com/boto3/latest/guide/credentials.html).
