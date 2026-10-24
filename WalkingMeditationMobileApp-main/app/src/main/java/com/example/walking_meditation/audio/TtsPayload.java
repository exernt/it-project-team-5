package com.example.walking_meditation.audio;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

/** Converts original, provider-independent narration into a backend request. */
public interface TtsPayload {
    String providerId();
    JSONObject toJson(String narration) throws JSONException, IOException;
}
