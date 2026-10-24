package com.example.walking_meditation.audio;

import android.content.Context;

import com.example.walking_meditation.BuildConfig;
import com.example.walking_meditation.utils.AuthenticatedHttpClient;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

public class TtsClient implements WalkAudioPreparer.AudioSource {

    // Leave headroom below the API's 3,000-character validation limit.
    static final int MAX_CHUNK_CODE_POINTS = 2800;
    private static final int SERVER_MAX_CODE_POINTS = 3000;
    private static final Pattern PAUSE = Pattern.compile("\\(pause\\)", Pattern.CASE_INSENSITIVE);

    private static final String TTS_URL =
            BuildConfig.SERVER_BASE_URL + "/tts";

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");

    private static OkHttpClient defaultClient(Context context) {
        return AuthenticatedHttpClient.configure(context, new OkHttpClient.Builder())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build(); }

    private final OkHttpClient httpClient;
    private final String endpoint;
    private final File cacheDirectory;
    private final TtsPayload payloadFactory;

    public void cancel() { httpClient.dispatcher().cancelAll(); }

    /** Blocking preparation API; call only on the preparation worker. */
    public List<File> prepare(String text) throws Exception {
        List<File> audio = new ArrayList<>();
        for (String chunk : splitNarration(text)) {
            if (Thread.currentThread().isInterrupted())
                throw new IOException("Preparation cancelled");
            audio.add(prepareChunk(payloadFactory.toJson(chunk)));
        }
        return audio;
    }

    private File prepareChunk(JSONObject payload) throws Exception {
        File output = new File(cacheDirectory, requestKey(payload) + ".mp3");
        if (output.isFile() && output.length() > 0) return output;
        Request.Builder request = new Request.Builder().url(endpoint)
                .header("Accept", "audio/mpeg")
                .post(RequestBody.create(payload.toString(), JSON));
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
        this(new File(context.getFilesDir(), "current_walk"), defaultClient(context), endpoint, payloadFactory);
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
        List<String> chunkKeys = new ArrayList<>();
        for (String chunk : splitNarration(narration))
            chunkKeys.add(requestKey(payloadFactory.toJson(chunk)));
        return sha256(String.join("\n", chunkKeys));
    }

    /** Split locally so each Lambda invocation remains bounded and returns one MP3 response. */
    static List<String> splitNarration(String narration) throws IOException {
        String remaining = narration == null ? "" : narration.trim();
        if (!hasSpokenText(remaining)) throw new IOException("Narration must contain spoken text");
        List<String> chunks = new ArrayList<>();
        while (remaining.codePointCount(0, remaining.length()) > MAX_CHUNK_CODE_POINTS) {
            int hardLimit = remaining.offsetByCodePoints(0, MAX_CHUNK_CODE_POINTS);
            Matcher marker = PAUSE.matcher(remaining);
            while (marker.find()) {
                if (marker.start() < hardLimit && hardLimit < marker.end()) {
                    hardLimit = marker.start();
                    break;
                }
                if (marker.start() >= hardLimit) break;
            }
            int split = naturalBoundary(remaining, hardLimit);
            String chunk = remaining.substring(0, split).trim();
            if (!hasSpokenText(chunk))
                throw new IOException("Narration cannot be split into valid spoken chunks");
            chunks.add(chunk);
            remaining = remaining.substring(split).trim();
        }
        if (hasSpokenText(remaining)) {
            chunks.add(remaining);
        } else if (!remaining.isEmpty() && !chunks.isEmpty()) {
            int last = chunks.size() - 1;
            String combined = chunks.get(last) + remaining;
            if (combined.codePointCount(0, combined.length()) > SERVER_MAX_CODE_POINTS)
                throw new IOException("Trailing pause markers exceed the server request limit");
            chunks.set(last, combined);
        }
        return chunks;
    }

    private static boolean hasSpokenText(String text) {
        return text != null && !PAUSE.matcher(text).replaceAll("").trim().isEmpty();
    }

    private static int naturalBoundary(String text, int hardLimit) {
        int hardCodePoints = text.codePointCount(0, hardLimit);
        int minimum = text.offsetByCodePoints(0, Math.max(1, hardCodePoints - 800));
        for (int index = hardLimit; index > minimum; ) {
            int point = text.codePointBefore(index);
            int width = Character.charCount(point);
            if (Character.isWhitespace(point)) return index;
            if (point == '.' || point == '!' || point == '?' || point == ';'
                    || point == ':' || point == ',') return index;
            index -= width;
        }
        return hardLimit;
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
