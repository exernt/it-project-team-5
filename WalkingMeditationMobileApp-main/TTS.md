# Serverless TTS client

`ScriptNarrationParser` preserves original narration (including `(pause)`) and route anchors. `ServerTtsPayload` sends only the provider-neutral API body `{"text":"..."}`. Polly voice, engine, language, SSML, and AWS credentials are server concerns and are not exposed to the Android app.

`TtsClient` splits long narration into requests of at most 2,800 Unicode code points, leaving headroom below the server's 3,000-character validation limit. It does not remove or interpret `(pause)` tokens. Each request returns one MP3, and the files are retained as an ordered list under the original `ScheduledNarration`. `MeditationPlaybackActivity` plays that list continuously before advancing to the next route anchor. This avoids server-side MP3 concatenation, ffmpeg, EC2-local files, and responses that outgrow a synchronous Lambda invocation.

The authenticated server converts pause tokens to fixed SSML breaks after XML-escaping all ordinary text. Consequently characters such as `&`, `<`, and `>` remain safe, while `(pause)` and `(PAUSE)` retain the intended 2.5-second silence.

The server TTS contract is selected in `ScriptResultActivity`; `WalkAudioPreparer` owns cancellation, progress, persistence, and readiness. The backend `/tts` endpoint accepts provider-neutral JSON and returns raw MP3 bytes. For example:

```java
TtsClient tts = new TtsClient(
        getApplicationContext(),
        new ServerTtsPayload(),
        BuildConfig.SERVER_BASE_URL + "/tts");
```

MP3 keys hash the endpoint, API contract version, and canonical request JSON for every chunk. Preparation includes those keys in the manifest identity. If server-side voice or pacing settings change without an API version change, clear the current walk cache (or bump `ServerTtsPayload.providerId`) during rollout so an identical saved walk does not retain the prior audio profile.

Polly contract references: [SynthesizeSpeech](https://docs.aws.amazon.com/polly/latest/APIReference/API_SynthesizeSpeech.html) and [SSML pauses](https://docs.aws.amazon.com/polly/latest/dg/break-tag.html).

## Walk lifecycle

The supported path is `PathSelectionActivity` -> `ScriptResultActivity` (prepare) -> `MeditationPlaybackActivity` (local playback). The home screen can resume a ready walk. The old `RouteOptionsActivity` and `ScriptActivity`, their exclusive UI resources, and loose-audio intent extras have been removed.

Preparation publishes a ready manifest only after every local file validates. Cancellation is checked after each download and serialized with the final readiness transition. Completion and early discard use `CurrentWalk.finish`, releasing any playback handle before deleting the current walk. Route-end narration (including the final leg end) and walk completion share a 10-metre arrival tolerance; off-route fixes cannot trigger arrival.

Unit tests cover arrival boundaries, cancellation during the final download, readiness failures, and release-before-delete behavior without Android audio hardware or AWS calls. Real phone/Polly acceptance checks are deferred until AWS migration; see the backend Polly guide.
