package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.walking_meditation.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Admin-only actions: reviewing and approving pending researcher
 * registrations, so this can be done from the app instead of the database.
 */
public final class AdminApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PENDING_RESEARCHERS_URL =
            BuildConfig.SERVER_BASE_URL + "/auth/admin/researchers/pending";
    private static final String VERIFY_RESEARCHER_URL_TEMPLATE =
            BuildConfig.SERVER_BASE_URL + "/auth/admin/researchers/%s/verify";
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";

    public static final class PendingResearcher {
        public final String userId;
        public final String email;
        public final @Nullable String institution;
        public final Instant requestedAt;

        public PendingResearcher(
                @NonNull String userId,
                @NonNull String email,
                @Nullable String institution,
                @NonNull Instant requestedAt
        ) {
            this.userId = userId;
            this.email = email;
            this.institution = institution;
            this.requestedAt = requestedAt;
        }
    }

    public interface PendingResearchersListener {
        void onLoaded(@NonNull List<PendingResearcher> researchers);
        void onFailure(@NonNull String message);
    }

    public interface ApproveListener {
        void onApproved();
        void onFailure(@NonNull String message);
    }

    private AdminApiClient() {}

    public static void fetchPendingResearchers(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull PendingResearchersListener listener
    ) {
        executeAuthenticatedGet(context.getApplicationContext(), client, true, listener);
    }

    public static void approveResearcher(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String userId,
            @NonNull ApproveListener listener
    ) {
        executeApprove(context.getApplicationContext(), client, userId, true, listener);
    }

    // --- GET pending researchers ---------------------------------------

    private static void executeAuthenticatedGet(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            boolean allowRefresh,
            @NonNull PendingResearchersListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        Request request = new Request.Builder()
                .url(PENDING_RESEARCHERS_URL)
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
                        refreshAndRetryGet(context, client, listener);
                        return;
                    }
                    String body = closedResponse.body() == null ? "" : closedResponse.body().string();
                    if (!closedResponse.isSuccessful()) {
                        listener.onFailure(errorMessage(body, closedResponse.code()));
                        return;
                    }
                    listener.onLoaded(parsePendingResearchers(body));
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }

    @NonNull
    private static List<PendingResearcher> parsePendingResearchers(@NonNull String responseBody) throws Exception {
        JSONArray array = new JSONObject(responseBody).getJSONArray("researchers");
        List<PendingResearcher> researchers = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            researchers.add(new PendingResearcher(
                    item.getString("user_id"),
                    item.getString("email"),
                    item.isNull("institution") ? null : item.getString("institution"),
                    OffsetDateTime.parse(item.getString("requested_at")).toInstant()
            ));
        }
        return researchers;
    }

    // --- POST approve researcher -----------------------------------------

    private static void executeApprove(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String userId,
            boolean allowRefresh,
            @NonNull ApproveListener listener
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            listener.onFailure("Your session has expired. Please sign in again.");
            return;
        }

        String url = String.format(VERIFY_RESEARCHER_URL_TEMPLATE, userId);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create("", null))
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
                        refreshAndRetryApprove(context, client, userId, listener);
                        return;
                    }
                    if (closedResponse.isSuccessful()) {
                        listener.onApproved();
                        return;
                    }
                    String body = closedResponse.body() == null ? "" : closedResponse.body().string();
                    listener.onFailure(errorMessage(body, closedResponse.code()));
                } catch (Exception e) {
                    listener.onFailure(e.getMessage() == null ? "Invalid response" : e.getMessage());
                }
            }
        });
    }

    // --- shared refresh-and-retry ----------------------------------------

    private static void refreshAndRetryGet(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull PendingResearchersListener listener
    ) {
        refreshAccessToken(context, client, new RefreshCallback() {
            @Override
            public void onRefreshed() {
                executeAuthenticatedGet(context, client, false, listener);
            }

            @Override
            public void onFailure(@NonNull String message) {
                listener.onFailure(message);
            }
        });
    }

    private static void refreshAndRetryApprove(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String userId,
            @NonNull ApproveListener listener
    ) {
        refreshAccessToken(context, client, new RefreshCallback() {
            @Override
            public void onRefreshed() {
                executeApprove(context, client, userId, false, listener);
            }

            @Override
            public void onFailure(@NonNull String message) {
                listener.onFailure(message);
            }
        });
    }

    private interface RefreshCallback {
        void onRefreshed();
        void onFailure(@NonNull String message);
    }

    private static void refreshAccessToken(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull RefreshCallback callback
    ) {
        String refreshToken = SessionManager.getRefreshToken(context);
        if (refreshToken == null || refreshToken.isEmpty()) {
            callback.onFailure("Your session has expired. Please sign in again.");
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
                    callback.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            callback.onFailure("Your session has expired. Please sign in again.");
                            return;
                        }
                        JSONObject body = new JSONObject(closedResponse.body().string());
                        String newAccessToken = body.getString("access_token");
                        String newRefreshToken = body.getString("refresh_token");
                        SessionManager.saveTokens(context, newAccessToken, newRefreshToken);
                        callback.onRefreshed();
                    } catch (Exception e) {
                        callback.onFailure(e.getMessage() == null ? "Invalid refresh response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
        }
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
        return "Request failed (" + statusCode + ")";
    }
}