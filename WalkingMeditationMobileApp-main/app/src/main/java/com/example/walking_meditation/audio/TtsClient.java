package com.example.walking_meditation.audio;

import android.content.Context;

import com.example.walking_meditation.BuildConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
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

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build();

    private final File cacheDirectory;

    public interface TtsCallback {
        void onSuccess(File audioFile);
        void onFailure(Exception error);
    }

    public TtsClient(Context context) {
        cacheDirectory = new File(context.getFilesDir(), "tts_cache");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create TTS cache");
        }
    }

    public Call download(
            String text,
            String speaker,
            String instructions,
            TtsCallback callback
    ) {
        try {
            // Include every audio-affecting parameter in the cache key.
            String cacheKey = sha256(
                    text + "\n" + speaker + "\n"
                            + instructions + "\nEnglish\n192k"
            );

            File outputFile = new File(
                    cacheDirectory, cacheKey + ".mp3"
            );

            // Reuse completed downloads.
            if (outputFile.isFile() && outputFile.length() > 0) {
                callback.onSuccess(outputFile);
                return null;
            }

            JSONObject payload = new JSONObject();
            payload.put("text", text);
            payload.put("language", "English");
            payload.put("speaker", speaker);
            payload.put("instruct", instructions);
            payload.put("bitrate", "192k");
            // The backend currently synthesizes at its default speed.

            RequestBody body = RequestBody.create(
                    payload.toString(), JSON
            );

            Request.Builder builder = new Request.Builder()
                    .url(TTS_URL)
                    .post(body);

            if (!BuildConfig.SERVER_ACCESS_TOKEN.isEmpty()) {
                builder.header(
                        "X-App-Token",
                        BuildConfig.SERVER_ACCESS_TOKEN
                );
            }

            Call call = httpClient.newCall(builder.build());

            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    callback.onFailure(e);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    File temporaryFile = new File(
                            cacheDirectory, cacheKey + ".part"
                    );

                    try (Response r = response) {
                        ResponseBody responseBody = r.body();

                        if (!r.isSuccessful() || responseBody == null) {
                            throw new IOException(
                                    "TTS server returned HTTP " + r.code()
                            );
                        }

                        try (InputStream in = responseBody.byteStream();
                             FileOutputStream out =
                                     new FileOutputStream(temporaryFile)) {

                            byte[] buffer = new byte[8192];
                            int count;

                            while ((count = in.read(buffer)) != -1) {
                                out.write(buffer, 0, count);
                            }
                        }

                        if (temporaryFile.length() == 0) {
                            throw new IOException("Empty TTS response");
                        }

                        if (!temporaryFile.renameTo(outputFile)) {
                            throw new IOException("Cannot finalize MP3");
                        }

                        callback.onSuccess(outputFile);

                    } catch (Exception e) {
                        temporaryFile.delete();
                        callback.onFailure(e);
                    }
                }
            });

            return call;

        } catch (Exception e) {
            callback.onFailure(e);
            return null;
        }
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
