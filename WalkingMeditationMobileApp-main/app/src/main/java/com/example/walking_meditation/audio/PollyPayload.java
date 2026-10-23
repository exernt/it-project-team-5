package com.example.walking_meditation.audio;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.regex.Pattern;

/** AWS-shaped JSON for the backend /tts Polly proxy, which returns raw MP3 bytes. */
public final class PollyPayload implements TtsPayload {
    private static final Pattern PAUSE = Pattern.compile("\\(pause\\)", Pattern.CASE_INSENSITIVE);
    private final String voiceId, languageCode, engine;
    private final int pauseMs;

    public PollyPayload() { this("Joanna", "en-US", "standard", 2500); }

    public PollyPayload(String voiceId, String languageCode, String engine, int pauseMs) {
        if (pauseMs < 0 || pauseMs > 10000)
            throw new IllegalArgumentException("Polly pause must be between 0 and 10000 ms");
        this.voiceId = voiceId;
        this.languageCode = languageCode;
        this.engine = engine;
        this.pauseMs = pauseMs;
    }

    @Override public String providerId() { return "polly-v1"; }

    @Override public JSONObject toJson(String narration) throws JSONException, IOException {
        if (narration == null || PAUSE.matcher(narration).replaceAll("").trim().isEmpty())
            throw new IOException("Polly narration must contain spoken text");
        String[] parts = PAUSE.split(narration.trim(), -1);
        StringBuilder ssml = new StringBuilder("<speak>");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) ssml.append("<break time=\"").append(pauseMs).append("ms\"/>");
            ssml.append(escapeXml(parts[i]));
        }
        ssml.append("</speak>");
        // The backend splits this block into requests that fit Polly's individual limits.
        if (ssml.codePointCount(0, ssml.length()) > 60000)
            throw new IOException("Narration exceeds the backend's 60000-character block limit");
        return new JSONObject().put("Text", ssml.toString()).put("TextType", "ssml")
                .put("VoiceId", voiceId).put("LanguageCode", languageCode)
                .put("Engine", engine).put("OutputFormat", "mp3");
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
