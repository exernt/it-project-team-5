package com.example.walking_meditation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PasswordResetLinkParserTest {
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz_ABCDEFG-1234567890";

    @Test
    public void extractsTokenFromCustomSchemeFragment() {
        assertEquals(
                TOKEN,
                PasswordResetLinkParser.extractToken(
                        "walkingmeditation://password-reset#token=" + TOKEN,
                        "api.example.com"
                )
        );
    }

    @Test
    public void extractsTokenFromExpectedHttpsAppLink() {
        assertEquals(
                TOKEN,
                PasswordResetLinkParser.extractToken(
                        "https://api.example.com/auth/password-reset/open#token=" + TOKEN,
                        "api.example.com"
                )
        );
    }

    @Test
    public void rejectsUnexpectedHttpsHost() {
        assertNull(PasswordResetLinkParser.extractToken(
                "https://attacker.example/auth/password-reset/open#token=" + TOKEN,
                "api.example.com"
        ));
    }

    @Test
    public void rejectsTokenInQueryString() {
        assertNull(PasswordResetLinkParser.extractToken(
                "walkingmeditation://password-reset?token=" + TOKEN,
                "api.example.com"
        ));
    }

    @Test
    public void rejectsMalformedOrShortToken() {
        assertNull(PasswordResetLinkParser.extractToken(
                "walkingmeditation://password-reset#token=short",
                "api.example.com"
        ));
    }
}
