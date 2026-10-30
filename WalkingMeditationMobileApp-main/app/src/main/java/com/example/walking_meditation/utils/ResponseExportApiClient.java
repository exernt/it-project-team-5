package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.walking_meditation.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Requests asynchronous CSV exports of questionnaire responses. */
public final class ResponseExportApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String RESPONSE_EXPORTS_URL =
            BuildConfig.SERVER_BASE_URL + "/response-exports";
    private static final String STUDIES_URL = BuildConfig.SERVER_BASE_URL + "/studies";

    public interface ExportListener {
        void onRequested(boolean cached);
        void onFailure(@NonNull String message);
    }

    private ResponseExportApiClient() {}

    public static void requestAllStudies(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull ExportListener listener
    ) {
        request(context, client, RESPONSE_EXPORTS_URL, listener);
    }

    public static void requestStudy(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull ExportListener listener
    ) {
        request(
                context,
                client,
                STUDIES_URL + "/" + studyId + "/response-export",
                listener
        );
    }

    private static void request(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String url,
            @NonNull ExportListener listener
    ) {
        OkHttpClient authenticatedClient = AuthenticatedHttpClient.configure(
                context,
                client.newBuilder()
        ).build();
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create("{}", JSON))
                .build();

        authenticatedClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                listener.onFailure(error.getMessage() == null
                        ? "Unable to contact the server"
                        : error.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    String responseBody = closedResponse.body() == null
                            ? ""
                            : closedResponse.body().string();
                    if (!closedResponse.isSuccessful()) {
                        listener.onFailure(errorMessage(responseBody, closedResponse.code()));
                        return;
                    }
                    listener.onRequested(new JSONObject(responseBody).optBoolean("cached", false));
                } catch (Exception error) {
                    listener.onFailure(error.getMessage() == null
                            ? "Invalid export response"
                            : error.getMessage());
                }
            }
        });
    }

    @NonNull
    private static String errorMessage(@NonNull String responseBody, int statusCode) {
        try {
            String detail = new JSONObject(responseBody).optString("detail", "");
            if (!detail.isEmpty()) {
                return detail;
            }
        } catch (Exception ignored) {
            // Fall through to the status-based message.
        }
        return "Export request failed (" + statusCode + ")";
    }
}
