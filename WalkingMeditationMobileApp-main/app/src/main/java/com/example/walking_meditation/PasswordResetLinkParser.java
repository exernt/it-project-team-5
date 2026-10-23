package com.example.walking_meditation;

import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URLDecoder;
import java.util.regex.Pattern;

final class PasswordResetLinkParser {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{40,128}");
    private static final String HTTPS_RESET_PATH = "/auth/password-reset/open";

    private PasswordResetLinkParser() {
    }

    @Nullable
    static String extractToken(@Nullable String rawUri, @Nullable String expectedHttpsHost) {
        if (rawUri == null || rawUri.isEmpty()) {
            return null;
        }

        try {
            URI uri = URI.create(rawUri);
            boolean isCustomScheme = "walkingmeditation".equalsIgnoreCase(uri.getScheme())
                    && "password-reset".equalsIgnoreCase(uri.getHost())
                    && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
            boolean isVerifiedHttps = "https".equalsIgnoreCase(uri.getScheme())
                    && expectedHttpsHost != null
                    && expectedHttpsHost.equalsIgnoreCase(uri.getHost())
                    && HTTPS_RESET_PATH.equals(uri.getPath());
            if (!isCustomScheme && !isVerifiedHttps) {
                return null;
            }

            String fragment = uri.getRawFragment();
            if (fragment == null) {
                return null;
            }
            for (String field : fragment.split("&")) {
                String[] pair = field.split("=", 2);
                if (pair.length == 2 && "token".equals(pair[0])) {
                    String token = URLDecoder.decode(pair[1], "UTF-8");
                    return TOKEN_PATTERN.matcher(token).matches() ? token : null;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
}
