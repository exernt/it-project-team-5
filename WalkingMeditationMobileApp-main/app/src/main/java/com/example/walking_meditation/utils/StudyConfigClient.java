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

public final class StudyConfigClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String CURRENT_STUDY_URL =
            BuildConfig.SERVER_BASE_URL + "/studies/current-script-parameters";
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";

    public interface Listener {
        void onLoaded(boolean participating, @NonNull String studyId, @NonNull String parameters);
        void onFailure(@NonNull String message);
    }

    private StudyConfigClient() {}

    public static void fetchCurrent(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull Listener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onLoaded(false, "", "");
            return;
        }
        executeCurrent(context.getApplicationContext(), client, listener, accessToken, true);
    }

    private static void executeCurrent(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull Listener listener,
            @NonNull String accessToken,
            boolean allowRefresh
    ) {
        Request request = new Request.Builder()
                .url(CURRENT_STUDY_URL)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Unable to check study status" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    if (closedResponse.code() == 401 && allowRefresh) {
                        refreshAndRetry(context, client, listener);
                        return;
                    }
                    if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                        listener.onFailure("Study status request failed (" + closedResponse.code() + ")");
                        return;
                    }

                    JSONObject body = new JSONObject(closedResponse.body().string());
                    boolean participating = body.optBoolean("participating", false);
                    listener.onLoaded(
                            participating,
                            body.optString("study_id", ""),
                            body.optString("script_generation_parameters", "")
                    );
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid study status response" : e.getMessage());
                }
            }
        });
    }

    private static void refreshAndRetry(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull Listener listener
    ) {
        String refreshToken = SessionManager.getRefreshToken(context);
        if (refreshToken == null || refreshToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("refresh_token", refreshToken);
            Request request = new Request.Builder()
                    .url(REFRESH_URL)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    listener.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            listener.onFailure("Your session has expired. Please sign in again.");
                            return;
                        }
                        JSONObject body = new JSONObject(closedResponse.body().string());
                        String newAccessToken = body.getString("access_token");
                        String newRefreshToken = body.getString("refresh_token");
                        SessionManager.saveTokens(context, newAccessToken, newRefreshToken);
                        executeCurrent(context, client, listener, newAccessToken, false);
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid refresh response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
        }
    }
}
