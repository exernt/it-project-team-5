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
    private static final String REGISTER_RESEARCHER_URL = BuildConfig.SERVER_BASE_URL + "/auth/register-researcher";
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";

    public interface CurrentUserListener {
        void onLoaded(@NonNull String role, boolean researcherVerified);
        void onFailure(@NonNull String message);
    }

    public interface RegisterResearcherListener {
        void onRegistered();
        void onFailure(@NonNull String message);
    }

    private UserApiClient() {}

    public static void fetchCurrentUser(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull CurrentUserListener listener
    ) {
        executeAuthenticatedRequest(context.getApplicationContext(), client, true, listener);
    }

    public static void registerAsResearcher(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @Nullable String institution,
            @NonNull RegisterResearcherListener listener
    ) {
        executeRegisterResearcher(context.getApplicationContext(), client, institution, true, listener);
    }

    private static void executeAuthenticatedRequest(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            boolean allowRefresh,
            @NonNull CurrentUserListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
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

    // --- register as researcher -------------------------------------------

    private static void executeRegisterResearcher(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @Nullable String institution,
            boolean allowRefresh,
            @NonNull RegisterResearcherListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("institution", institution == null || institution.isEmpty() ? JSONObject.NULL : institution);

            Request request = new Request.Builder()
                    .url(REGISTER_RESEARCHER_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .post(RequestBody.create(payload.toString(), JSON))
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
                            refreshAndRetryRegisterResearcher(context, client, institution, listener);
                            return;
                        }
                        if (closedResponse.isSuccessful()) {
                            SessionManager.saveUserInfo(context, "researcher", false);
                            listener.onRegistered();
                            return;
                        }
                        String body = closedResponse.body() == null ? "" : closedResponse.body().string();
                        listener.onFailure(registerErrorMessage(body, closedResponse.code()));
                    } catch (Exception e) {
                        listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure(e.getMessage() == null ? "Unable to prepare the request" : e.getMessage());
        }
    }

    @NonNull
    private static String registerErrorMessage(@NonNull String responseBody, int statusCode) {
        try {
            String detail = new JSONObject(responseBody).optString("detail", "");
            if (!detail.isEmpty()) {
                return detail;
            }
        } catch (Exception ignored) {
            // Fall through to the status-based message.
        }
        return "Request failed (" + statusCode + ")";
    }

    private static void refreshAndRetryRegisterResearcher(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @Nullable String institution,
            @NonNull RegisterResearcherListener listener
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
                        SessionManager.saveTokens(
                                context,
                                body.getString("access_token"),
                                body.getString("refresh_token")
                        );
                        executeRegisterResearcher(context, client, institution, false, listener);
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