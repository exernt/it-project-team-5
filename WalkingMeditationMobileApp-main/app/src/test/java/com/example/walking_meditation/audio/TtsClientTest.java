package com.example.walking_meditation.audio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class TtsClientTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void sendsBackendContractRemovesPauseMarkersAndReusesCompletedAudio() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            try {
                JSONObject payload = new JSONObject(buffer.readUtf8());
                assertEquals("Welcome.\n\n Walk.", payload.getString("text"));
                assertEquals("Ryan", payload.getString("speaker"));
                assertEquals("192k", payload.getString("bitrate"));
                assertFalse(payload.has("speed"));
            } catch (org.json.JSONException error) { throw new IOException(error); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", "audio/mpeg")
                    .body(ResponseBody.create(new byte[]{1, 2, 3}, MediaType.get("audio/mpeg"))).build();
        }).build();
        TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/tts", new QwenPayload());
        File first = client.prepare("Welcome.(pause) Walk.");
        assertEquals(3, first.length());
        assertEquals(first, client.prepare("Welcome.(pause) Walk."));
        assertEquals(1, requests.get());
        assertEquals(1, temp.getRoot().listFiles().length);
    }

    @Test public void rejectsServerErrorsNonAudioAndEmptyBodiesWithoutCaching() throws Exception {
        for (int code : new int[]{200, 500}) {
            for (String type : new String[]{"audio/mpeg", "application/json"}) {
                OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
                        new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                                .code(code).message("test").header("Content-Type", type)
                                .body(ResponseBody.create(new byte[0], MediaType.get(type))).build()).build();
                TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/tts", new QwenPayload());
                assertThrows(IOException.class, () -> client.prepare("Welcome."));
                assertEquals(0, temp.getRoot().listFiles().length);
            }
        }
    }

    @Test public void cacheIdentityIncludesProviderEndpointVoiceAndPauseSettings() throws Exception {
        OkHttpClient http = new OkHttpClient();
        String text = "One.(pause)Two.";
        TtsClient qwen = new TtsClient(temp.getRoot(), http, "http://localhost/tts", new QwenPayload());
        assertNotEquals(qwen.cacheKey(text), new TtsClient(temp.getRoot(), http,
                "http://localhost/tts", new QwenPayload("English", "Ryan", "Gentle", "192k")).cacheKey(text));
        assertNotEquals(qwen.cacheKey(text), new TtsClient(temp.getRoot(), http,
                "http://localhost/other", new QwenPayload()).cacheKey(text));
        TtsClient polly = new TtsClient(temp.getRoot(), http, "http://localhost/tts", new PollyPayload());
        assertNotEquals(qwen.cacheKey(text), polly.cacheKey(text));
        assertNotEquals(polly.cacheKey(text), new TtsClient(temp.getRoot(), http,
                "http://localhost/tts", new PollyPayload("Joanna", "en-US", "standard", 1000)).cacheKey(text));
    }

    @Test public void clientSupportsPollyWithoutChangingTransportOrOriginalNarration() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            try {
                JSONObject json = new JSONObject(buffer.readUtf8());
                assertEquals("<speak>One.<break time=\"2500ms\"/>Two.</speak>", json.getString("Text"));
            } catch (org.json.JSONException error) { throw new IOException(error); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", "audio/mpeg")
                    .body(ResponseBody.create(new byte[]{1}, MediaType.get("audio/mpeg"))).build();
        }).build();
        TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/polly", new PollyPayload());
        assertEquals(1, client.prepare("One.(pause)Two.").length());
        client.prepare("One.(pause)Two.");
        assertEquals(1, requests.get());
    }
}
