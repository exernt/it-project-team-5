# Frontend TTS payloads

`ScriptNarrationParser` preserves original narration (including `(pause)`) and route anchors. Preparation passes that text to `TtsClient`, which delegates request construction and provider-specific validation to `TtsPayload`. Transport, MP3 downloads, and playback are shared.

`QwenPayload` preserves the previous Qwen contract and turns pause markers into paragraph breaks. Its default settings remain English / Ryan / Calm and clear / 192k. Constructor arguments allow different settings.

`PollyPayload` converts pause markers to SSML breaks (2500 ms by default), XML-escapes ordinary narration, and emits AWS-shaped fields: `Text`, `TextType`, `VoiceId`, `LanguageCode`, `Engine`, and `OutputFormat`. It validates the backend 60,000-character block limit and pause duration. The backend splits oversized narration to meet Polly per-request limits and returns one joined MP3. Voice/engine compatibility must be validated by the backend.

Polly is selected in `ScriptResultActivity`; `WalkAudioPreparer` owns cancellation, progress, persistence, and readiness. The backend `/tts` endpoint accepts its AWS-shaped JSON and returns raw MP3 bytes. For example:

```java
TtsClient tts = new TtsClient(
        getApplicationContext(),
        new PollyPayload("Joanna", "en-US", "standard", 2500),
        BuildConfig.SERVER_BASE_URL + "/tts");
```

AWS credentials stay on the server. `QwenPayload` remains available as a frontend adapter, but the migrated backend no longer supports Qwen synthesis. See `../WalkingMeditationServer-main/READList/Polly.md` for AWS setup.

MP3 keys hash the endpoint, provider ID, and canonical final JSON. Preparation includes every narration's key in the manifest identity, so changing provider or speech settings replaces the previous walk instead of reusing stale audio. Do not change payload settings during a preparation run.

Polly contract references: [SynthesizeSpeech](https://docs.aws.amazon.com/polly/latest/APIReference/API_SynthesizeSpeech.html) and [SSML pauses](https://docs.aws.amazon.com/polly/latest/dg/break-tag.html).

## Walk lifecycle

The supported path is `PathSelectionActivity` -> `ScriptResultActivity` (prepare) -> `MeditationPlaybackActivity` (local playback). The home screen can resume a ready walk. The old `RouteOptionsActivity` and `ScriptActivity`, their exclusive UI resources, and loose-audio intent extras have been removed.

Preparation publishes a ready manifest only after every local file validates. Cancellation is checked after each download and serialized with the final readiness transition. Completion and early discard use `CurrentWalk.finish`, releasing any playback handle before deleting the current walk. Route-end narration (including the final leg end) and walk completion share a 10-metre arrival tolerance; off-route fixes cannot trigger arrival.

Unit tests cover arrival boundaries, cancellation during the final download, readiness failures, and release-before-delete behavior without Android audio hardware or AWS calls. Real phone/Polly acceptance checks are deferred until AWS migration; see the backend Polly guide.
