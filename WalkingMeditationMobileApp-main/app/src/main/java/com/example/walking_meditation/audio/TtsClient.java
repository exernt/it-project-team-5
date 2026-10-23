package com.example.walking_meditation.audio;

import android.content.Context;

import com.example.walking_meditation.BuildConfig;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class TtsClient {

    private static final String TTS_URL =
            BuildConfig.SERVER_BASE_URL + "/tts";

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private static OkHttpClient defaultClient() { return new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build(); }

    private final OkHttpClient httpClient;
    private final String endpoint;
    private final File cacheDirectory;
    private final TtsPayload payloadFactory;

    public void cancel() { httpClient.dispatcher().cancelAll(); }

    /** Blocking preparation API; call only on the preparation worker. */
    public File prepare(String text) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Preparation cancelled");
        JSONObject payload = payloadFactory.toJson(text);
        File output = new File(cacheDirectory, requestKey(payload) + ".mp3");
        if (output.isFile() && output.length() > 0) return output;
        Request.Builder request = new Request.Builder().url(endpoint)
                .post(RequestBody.create(payload.toString(), JSON));
        if (!BuildConfig.SERVER_ACCESS_TOKEN.isEmpty())
            request.header("X-App-Token", BuildConfig.SERVER_ACCESS_TOKEN);
        File temporary = new File(cacheDirectory, output.getName() + ".part");
        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody body = response.body();
            String type = response.header("Content-Type", "");
            if (!response.isSuccessful() || body == null || !type.startsWith("audio/"))
                throw new IOException("TTS server returned HTTP " + response.code() + " without audio");
            try (InputStream in = body.byteStream(); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Preparation cancelled");
                    out.write(buffer, 0, count);
                }
            }
            if (temporary.length() == 0 || !temporary.renameTo(output))
                throw new IOException("Cannot save narration MP3");
            return output;
        } finally { temporary.delete(); }
    }

    public TtsClient(Context context, TtsPayload payloadFactory) {
        this(context, payloadFactory, TTS_URL);
    }

    public TtsClient(Context context, TtsPayload payloadFactory, String endpoint) {
        this(new File(context.getFilesDir(), "current_walk"), defaultClient(), endpoint, payloadFactory);
    }

    TtsClient(File directory, OkHttpClient httpClient, String endpoint, TtsPayload payloadFactory) {
        this.cacheDirectory = directory;
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.payloadFactory = java.util.Objects.requireNonNull(payloadFactory);
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create TTS cache");
        }
    }

    /** Also included in the walk identity, so saved manifests cannot bypass configuration changes. */
    public String cacheKey(String narration) throws Exception {
        return requestKey(payloadFactory.toJson(narration));
    }

    private String requestKey(JSONObject payload) throws Exception {
        return sha256(endpoint + "\n" + payloadFactory.providerId() + "\n" + canonicalJson(payload));
    }

    // JSONObject property order is not part of its contract. Hash equivalent requests identically.
    private static String canonicalJson(Object value) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            List<String> entries = new ArrayList<>();
            for (String key : keys)
                entries.add(JSONObject.quote(key) + ":" + canonicalJson(object.get(key)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) entries.add(canonicalJson(array.get(i)));
            return "[" + String.join(",", entries) + "]";
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    private static String sha256(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));

        StringBuilder result = new StringBuilder();

        for (byte b : digest) {
            result.append(String.format("%02x", b & 0xff));
        }

        return result.toString();
    }
}
