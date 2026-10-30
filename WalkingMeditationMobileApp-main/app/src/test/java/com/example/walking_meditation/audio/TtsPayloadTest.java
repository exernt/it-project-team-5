package com.example.walking_meditation.audio;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class TtsPayloadTest {
    @Test public void serverPayloadPreservesPauseMarkersAndSpecialCharacters() throws Exception {
        String narration = "(pause)A & B <tag> \"quote\" 'apostrophe'(PAUSE) End.";
        JSONObject json = new ServerTtsPayload().toJson("  " + narration + "  ");
        assertEquals(narration, json.getString("text"));
        assertEquals("male", json.getString("voice"));
        assertEquals(2, json.length());
        assertEquals("meditation-server-tts-v2", new ServerTtsPayload().providerId());
    }

    @Test public void serverPayloadEnforcesThePublicApiBoundary() throws Exception {
        ServerTtsPayload payload = new ServerTtsPayload();
        assertThrows(IOException.class, () -> payload.toJson(null));
        assertThrows(IOException.class, () -> payload.toJson("(pause)(PAUSE)"));
        assertThrows(IOException.class, () -> payload.toJson("a".repeat(3001)));
        assertEquals(3000, payload.toJson("a".repeat(3000)).getString("text").length());
    }

    @Test public void serverPayloadCanRequestFemaleStudyVoice() throws Exception {
        assertEquals("female", new ServerTtsPayload("female")
                .toJson("A quiet walk.").getString("voice"));
    }
}
