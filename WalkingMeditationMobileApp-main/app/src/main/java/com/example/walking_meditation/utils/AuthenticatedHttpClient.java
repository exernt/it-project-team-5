package com.example.walking_meditation.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.walking_meditation.BuildConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Adds the current user bearer token and rotates it once after a 401 response. */
public final class AuthenticatedHttpClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String REFRESH_URL = BuildConfig.SERVER_BASE_URL + "/auth/refresh";
    private static final Object REFRESH_LOCK = new Object();
    private static final OkHttpClient REFRESH_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();

    private AuthenticatedHttpClient() {}

    @NonNull
    public static OkHttpClient.Builder configure(
            @NonNull Context context,
            @NonNull OkHttpClient.Builder builder
    ) {
        Context applicationContext = context.getApplicationContext();

        builder.addInterceptor(chain -> {
            Request request = chain.request();
            if (request.header("Authorization") == null) {
                String accessToken = SessionManager.getAccessToken(applicationContext);
                if (accessToken != null && !accessToken.isEmpty()) {
                    request = withBearerToken(request, accessToken);
                }
            }
            return chain.proceed(request);
        });

        builder.authenticator((route, response) -> {
            if (responseCount(response) >= 2) {
                return null;
            }

            String accessToken = refreshAccessToken(
                    applicationContext,
                    response.request().header("Authorization")
            );
            return accessToken == null
                    ? null
                    : withBearerToken(response.request(), accessToken);
        });

        return builder;
    }

    @NonNull
    private static Request withBearerToken(@NonNull Request request, @NonNull String accessToken) {
        return request.newBuilder()
                .header("Authorization", "Bearer " + accessToken)
                .build();
    }

    @Nullable
    private static String refreshAccessToken(
            @NonNull Context context,
            @Nullable String failedAuthorization
    ) {
        synchronized (REFRESH_LOCK) {
            String currentAccessToken = SessionManager.getAccessToken(context);
            if (currentAccessToken != null
                    && !currentAccessToken.isEmpty()
                    && !("Bearer " + currentAccessToken).equals(failedAuthorization)) {
                return currentAccessToken;
            }

            String refreshToken = SessionManager.getRefreshToken(context);
            if (refreshToken == null || refreshToken.isEmpty()) {
                return null;
            }

            try {
                JSONObject payload = new JSONObject();
                payload.put("refresh_token", refreshToken);
                Request request = new Request.Builder()
                        .url(REFRESH_URL)
                        .post(RequestBody.create(payload.toString(), JSON))
                        .build();

                try (Response response = REFRESH_CLIENT.newCall(request).execute()) {
                    if (response.code() == 401) {
                        SessionManager.clearSession(context);
                        return null;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        return null;
                    }

                    JSONObject body = new JSONObject(response.body().string());
                    String newAccessToken = body.getString("access_token");
                    String newRefreshToken = body.getString("refresh_token");
                    SessionManager.saveTokens(context, newAccessToken, newRefreshToken);
                    return newAccessToken;
                }
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static int responseCount(@NonNull Response response) {
        int count = 1;
        Response prior = response.priorResponse();
        while (prior != null) {
            count++;
            prior = prior.priorResponse();
        }
        return count;
    }
}
