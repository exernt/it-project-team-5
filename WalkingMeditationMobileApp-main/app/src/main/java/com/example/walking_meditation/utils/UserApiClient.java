package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

public final class UserApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ME_URL = BuildConfig.SERVER_BASE_URL + "/auth/me";
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";

    public interface CurrentUserListener {
        void onLoaded(@NonNull String role, boolean researcherVerified);
        void onFailure(@NonNull String message);

        default void onSessionExpired() {
            onFailure("Your session has expired. Please sign in again.");
        }
    }

    private UserApiClient() {}

    public static void fetchCurrentUser(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull CurrentUserListener listener
    ) {
        executeAuthenticatedRequest(context.getApplicationContext(), client, true, listener);
    }

    private static void executeAuthenticatedRequest(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            boolean allowRefresh,
            @NonNull CurrentUserListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            if (allowRefresh) {
                refreshAndRetry(context, client, listener);
            } else {
                listener.onSessionExpired();
            }
            return;
        }

        Request request = new Request.Builder()
                .url(ME_URL)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onFailure(e.getMessage() == null ? "Unable to contact the server" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    if (closedResponse.code() == 401 && allowRefresh) {
                        refreshAndRetry(context, client, listener);
                        return;
                    }
                    if (closedResponse.body() == null) {
                        listener.onFailure("Unable to load account info (" + closedResponse.code() + ")");
                        return;
                    }
                    String responseBody = closedResponse.body().string();
                    if (!closedResponse.isSuccessful()) {
                        listener.onFailure("Unable to load account info (" + closedResponse.code() + ")");
                        return;
                    }
                    JSONObject item = new JSONObject(responseBody);
                    String role = item.getString("role");
                    boolean verified = !item.isNull("verified_at");
                    listener.onLoaded(role, verified);
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid account response" : e.getMessage());
                }
            }
        });
    }

    private static void refreshAndRetry(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull CurrentUserListener listener
    ) {
        String refreshToken = SessionManager.getRefreshToken(context);
        if (refreshToken == null || refreshToken.isEmpty()) {
            listener.onSessionExpired();
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
                        if (closedResponse.code() == 401) {
                            listener.onSessionExpired();
                            return;
                        }
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            listener.onFailure(
                                    "Unable to refresh session (" + closedResponse.code() + ")"
                            );
                            return;
                        }
                        JSONObject body = new JSONObject(closedResponse.body().string());
                        SessionManager.saveTokens(
                                context,
                                body.getString("access_token"),
                                body.getString("refresh_token")
                        );
                        executeAuthenticatedRequest(context, client, false, listener);
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
