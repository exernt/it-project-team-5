package com.example.walking_meditation.audio;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

public final class QwenPayload implements TtsPayload {
    private final String language, speaker, instructions, bitrate;

    public QwenPayload() { this("English", "Ryan", "Calm and clear", "192k"); }

    public QwenPayload(String language, String speaker, String instructions, String bitrate) {
        this.language = language;
        this.speaker = speaker;
        this.instructions = instructions;
        this.bitrate = bitrate;
    }

    @Override public String providerId() { return "qwen-v1"; }

    @Override public JSONObject toJson(String narration) throws JSONException, IOException {
        String spoken = narration == null ? "" : narration.replaceAll("(?i)\\(pause\\)", "\n\n").trim();
        if (spoken.isEmpty() || spoken.length() > 5000)
            throw new IOException("Qwen narration must contain 1–5000 characters");
        return new JSONObject().put("text", spoken).put("language", language)
                .put("speaker", speaker).put("instruct", instructions).put("bitrate", bitrate);
    }
}
