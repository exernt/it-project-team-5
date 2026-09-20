package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.walking_meditation.BuildConfig;
import com.example.walking_meditation.models.Study;
import com.example.walking_meditation.models.StudyEnrollment;

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

public final class StudyApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String STUDIES_URL = BuildConfig.SERVER_BASE_URL + "/studies";
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";

    public interface StudyListListener {
        void onLoaded(@NonNull List<Study> studies);
        void onFailure(@NonNull String message);
    }

    public interface StudyListener {
        void onLoaded(@NonNull Study study);
        void onFailure(@NonNull String message);
    }

    public interface JoinListener {
        void onJoined();
        void onFailure(@NonNull String message);
    }

    public interface CurrentStudyListener {
        void onLoaded(@Nullable StudyEnrollment enrollment);
        void onFailure(@NonNull String message);
    }

    public interface LeaveListener {
        void onLeft();
        void onFailure(@NonNull String message);
    }

    private interface ResponseHandler {
        void handle(@NonNull String responseBody) throws Exception;
        void fail(@NonNull String message);
    }

    private StudyApiClient() {}

    public static void fetchOpenStudies(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull StudyListListener listener
    ) {
        executeAuthenticatedRequest(
                context.getApplicationContext(),
                client,
                STUDIES_URL,
                null,
                true,
                new ResponseHandler() {
                    @Override
                    public void handle(@NonNull String responseBody) throws Exception {
                        JSONArray items = new JSONObject(responseBody).getJSONArray("studies");
                        List<Study> openStudies = new ArrayList<>();
                        for (int index = 0; index < items.length(); index++) {
                            Study study = parseStudy(items.getJSONObject(index));
                            if (study.isOpen()) {
                                openStudies.add(study);
                            }
                        }
                        listener.onLoaded(openStudies);
                    }

                    @Override
                    public void fail(@NonNull String message) {
                        listener.onFailure(message);
                    }
                }
        );
    }

    public static void fetchStudy(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull StudyListener listener
    ) {
        executeAuthenticatedRequest(
                context.getApplicationContext(),
                client,
                STUDIES_URL + "/" + studyId,
                null,
                true,
                new ResponseHandler() {
                    @Override
                    public void handle(@NonNull String responseBody) throws Exception {
                        listener.onLoaded(parseStudy(new JSONObject(responseBody)));
                    }

                    @Override
                    public void fail(@NonNull String message) {
                        listener.onFailure(message);
                    }
                }
        );
    }

    public static void joinStudy(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull JoinListener listener
    ) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("agrees_to_consent", true);
            executeAuthenticatedRequest(
                    context.getApplicationContext(),
                    client,
                    STUDIES_URL + "/" + studyId + "/join",
                    payload.toString(),
                    true,
                    new ResponseHandler() {
                        @Override
                        public void handle(@NonNull String responseBody) {
                            listener.onJoined();
                        }

                        @Override
                        public void fail(@NonNull String message) {
                            listener.onFailure(message);
                        }
                    }
            );
        } catch (Exception e) {
            listener.onFailure("Unable to prepare the join request");
        }
    }

    public static void fetchCurrentEnrollment(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull CurrentStudyListener listener
    ) {
        executeAuthenticatedRequest(
                context.getApplicationContext(),
                client,
                STUDIES_URL + "/mine",
                null,
                true,
                new ResponseHandler() {
                    @Override
                    public void handle(@NonNull String responseBody) throws Exception {
                        JSONArray items = new JSONObject(responseBody).getJSONArray("studies");
                        StudyEnrollment current = null;
                        for (int index = 0; index < items.length(); index++) {
                            JSONObject item = items.getJSONObject(index);
                            String status = item.getString("status");
                            boolean isCurrentStatus = "recruiting".equals(status)
                                    || "active".equals(status);
                            if (!isCurrentStatus || !item.isNull("withdrawn_at")) {
                                continue;
                            }

                            Instant registeredAt = OffsetDateTime.parse(
                                    item.getString("registered_at")
                            ).toInstant();
                            Study study = new Study(
                                    item.getString("study_id"),
                                    item.getString("name"),
                                    item.getInt("duration"),
                                    item.optString("description", ""),
                                    status
                            );
                            if (current == null
                                    || registeredAt.isAfter(current.getRegisteredAt())) {
                                current = new StudyEnrollment(study, registeredAt);
                            }
                        }
                        listener.onLoaded(current);
                    }

                    @Override
                    public void fail(@NonNull String message) {
                        listener.onFailure(message);
                    }
                }
        );
    }

    public static void leaveStudy(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String studyId,
            @NonNull LeaveListener listener
    ) {
        executeAuthenticatedRequest(
                context.getApplicationContext(),
                client,
                STUDIES_URL + "/" + studyId + "/leave",
                "{}",
                true,
                new ResponseHandler() {
                    @Override
                    public void handle(@NonNull String responseBody) {
                        listener.onLeft();
                    }

                    @Override
                    public void fail(@NonNull String message) {
                        listener.onFailure(message);
                    }
                }
        );
    }

    @NonNull
    private static Study parseStudy(@NonNull JSONObject item) throws Exception {
        return new Study(
                item.getString("id"),
                item.getString("name"),
                item.getInt("duration"),
                item.optString("description", ""),
                item.getString("status")
        );
    }

    private static void executeAuthenticatedRequest(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String url,
            @Nullable String requestBody,
            boolean allowRefresh,
            @NonNull ResponseHandler handler
    ) {
        String accessToken = SessionManager.getAccessToken(context);
        if (accessToken == null || accessToken.isEmpty()) {
            handler.fail("Your session has expired. Please sign in again.");
            return;
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken);
        if (requestBody == null) {
            requestBuilder.get();
        } else {
            requestBuilder.post(RequestBody.create(requestBody, JSON));
        }
        Request request = requestBuilder.build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                handler.fail(e.getMessage() == null ? "Unable to contact the server" : e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response closedResponse = response) {
                    if (closedResponse.code() == 401 && allowRefresh) {
                        refreshAndRetry(context, client, url, requestBody, handler);
                        return;
                    }
                    if (closedResponse.body() == null) {
                        handler.fail("Study request failed (" + closedResponse.code() + ")");
                        return;
                    }
                    String responseBody = closedResponse.body().string();
                    if (!closedResponse.isSuccessful()) {
                        handler.fail(errorMessage(responseBody, closedResponse.code()));
                        return;
                    }
                    handler.handle(responseBody);
                } catch (Exception e) {
                    handler.fail(e.getMessage() == null ? "Invalid study response" : e.getMessage());
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
        return "Study request failed (" + statusCode + ")";
    }

    private static void refreshAndRetry(
            @NonNull Context context,
            @NonNull OkHttpClient client,
            @NonNull String url,
            @Nullable String requestBody,
            @NonNull ResponseHandler handler
    ) {
        String refreshToken = SessionManager.getRefreshToken(context);
        if (refreshToken == null || refreshToken.isEmpty()) {
            handler.fail("Your session has expired. Please sign in again.");
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
                    handler.fail(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (Response closedResponse = response) {
                        if (!closedResponse.isSuccessful() || closedResponse.body() == null) {
                            handler.fail("Your session has expired. Please sign in again.");
                            return;
                        }
                        JSONObject body = new JSONObject(closedResponse.body().string());
                        String newAccessToken = body.getString("access_token");
                        String newRefreshToken = body.getString("refresh_token");
                        SessionManager.saveTokens(context, newAccessToken, newRefreshToken);
                        executeAuthenticatedRequest(
                                context,
                                client,
                                url,
                                requestBody,
                                false,
                                handler
                        );
                    } catch (Exception e) {
                        handler.fail(e.getMessage() == null ? "Invalid refresh response" : e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            handler.fail(e.getMessage() == null ? "Unable to refresh session" : e.getMessage());
        }
    }
}
