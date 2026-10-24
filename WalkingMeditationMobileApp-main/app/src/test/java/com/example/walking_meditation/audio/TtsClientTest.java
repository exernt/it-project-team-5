package com.example.walking_meditation.audio;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class TtsClientTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void sendsNeutralContractPreservesPausesAndReusesCompletedAudio() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            try {
                JSONObject payload = new JSONObject(buffer.readUtf8());
                assertEquals("Welcome.(pause) Walk.", payload.getString("text"));
                assertEquals(1, payload.length());
            } catch (org.json.JSONException error) { throw new IOException(error); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", "audio/mpeg")
                    .body(ResponseBody.create(new byte[]{1, 2, 3}, MediaType.get("audio/mpeg"))).build();
        }).build();
        TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/tts",
                new ServerTtsPayload());
        List<File> first = client.prepare("Welcome.(pause) Walk.");
        assertEquals(1, first.size());
        assertEquals(3, first.get(0).length());
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
                TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/tts",
                        new ServerTtsPayload());
                assertThrows(IOException.class, () -> client.prepare("Welcome."));
                assertEquals(0, temp.getRoot().listFiles().length);
            }
        }
    }

    @Test public void cacheIdentityIncludesEndpointContractAndEveryChunk() throws Exception {
        OkHttpClient http = new OkHttpClient();
        TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/tts",
                new ServerTtsPayload());
        assertNotEquals(client.cacheKey("One."), client.cacheKey("Two."));
        assertNotEquals(client.cacheKey("One."), new TtsClient(temp.getRoot(), http,
                "http://localhost/other", new ServerTtsPayload()).cacheKey("One."));
        assertNotEquals(client.cacheKey("a".repeat(2800)), client.cacheKey("a".repeat(2801)));
    }

    @Test public void splitsLongNarrationWithoutLosingPauseTokens() throws Exception {
        String narration = "a".repeat(2790) + "(pause)" + "b".repeat(100);
        List<String> sent = new ArrayList<>();
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            try {
                sent.add(new JSONObject(buffer.readUtf8()).getString("text"));
            } catch (org.json.JSONException error) { throw new IOException(error); }
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").header("Content-Type", "audio/mpeg")
                    .body(ResponseBody.create(new byte[]{1}, MediaType.get("audio/mpeg"))).build();
        }).build();
        TtsClient client = new TtsClient(temp.getRoot(), http, "http://localhost/tts",
                new ServerTtsPayload());
        List<File> files = client.prepare(narration);
        assertEquals(2, files.size());
        assertEquals(narration, String.join("", sent));
        assertTrue(sent.stream().allMatch(text ->
                text.codePointCount(0, text.length()) <= TtsClient.MAX_CHUNK_CODE_POINTS));
        assertTrue(sent.stream().anyMatch(text -> text.contains("(pause)")));
    }
}
