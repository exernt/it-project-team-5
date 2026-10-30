package com.example.walking_meditation.audio;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.regex.Pattern;

/** Provider-neutral contract for meditation-server's authenticated /tts endpoint. */
public final class ServerTtsPayload implements TtsPayload {
    private static final int MAX_TEXT_CODE_POINTS = 3000;
    private static final Pattern PAUSE = Pattern.compile("\\(pause\\)", Pattern.CASE_INSENSITIVE);
    private final String voice;

    public ServerTtsPayload() {
        this("male");
    }

    public ServerTtsPayload(String voice) {
        this.voice = "female".equals(voice) ? "female" : "male";
    }

    @Override public String providerId() { return "meditation-server-tts-v2"; }

    @Override public JSONObject toJson(String narration) throws JSONException, IOException {
        String text = narration == null ? "" : narration.trim();
        if (PAUSE.matcher(text).replaceAll("").trim().isEmpty())
            throw new IOException("Narration must contain spoken text");
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS)
            throw new IOException("Narration chunk exceeds the server's 3000-character limit");
        return new JSONObject().put("text", text).put("voice", voice);
    }
}
