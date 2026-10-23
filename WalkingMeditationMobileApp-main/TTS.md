# Frontend TTS payloads

`ScriptNarrationParser` preserves original narration (including `(pause)`) and route anchors. Preparation passes that text to `TtsClient`, which delegates request construction and provider-specific validation to `TtsPayload`. Transport, cancellation, MP3 downloads, and playback are shared.

`QwenPayload` preserves the previous Qwen contract and turns pause markers into paragraph breaks. Its default settings remain English / Ryan / Calm and clear / 192k. Constructor arguments allow different settings.

`PollyPayload` converts pause markers to SSML breaks (2500 ms by default), XML-escapes ordinary narration, and emits AWS-shaped fields: `Text`, `TextType`, `VoiceId`, `LanguageCode`, `Engine`, and `OutputFormat`. It validates synchronous request length and pause duration. Voice/engine compatibility must be validated by the backend.

Polly is selected in `ScriptResultActivity` and the legacy `ScriptActivity`. The backend `/tts` endpoint accepts its AWS-shaped JSON and returns raw MP3 bytes. For example:

```java
TtsClient tts = new TtsClient(
        getApplicationContext(),
        new PollyPayload("Joanna", "en-US", "standard", 2500),
        BuildConfig.SERVER_BASE_URL + "/tts");
```

AWS credentials stay on the server. `QwenPayload` remains available as a frontend adapter, but the migrated backend no longer supports Qwen synthesis. See `../WalkingMeditationServer-main/READList/Polly.md` for AWS setup.

MP3 keys hash the endpoint, provider ID, and canonical final JSON. Preparation includes every narration's key in the manifest identity, so changing provider or speech settings replaces the previous walk instead of reusing stale audio. Do not change payload settings during a preparation run.

Polly contract references: [SynthesizeSpeech](https://docs.aws.amazon.com/polly/latest/APIReference/API_SynthesizeSpeech.html) and [SSML pauses](https://docs.aws.amazon.com/polly/latest/dg/break-tag.html).
