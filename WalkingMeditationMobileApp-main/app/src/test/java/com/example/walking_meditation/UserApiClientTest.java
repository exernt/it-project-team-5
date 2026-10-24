package com.example.walking_meditation.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UserApiClientTest {

    @Test
    public void usesDetailFromResponseBodyWhenPresent() {
        String message = UserApiClient.registerErrorMessage(
                "{\"detail\": \"Researcher profile already exists for this account\"}", 409
        );

        assertEquals("Researcher profile already exists for this account", message);
    }

    @Test
    public void fallsBackToStatusCodeWhenDetailMissing() {
        String message = UserApiClient.registerErrorMessage("{}", 500);

        assertTrue(message.contains("500"));
    }

    @Test
    public void fallsBackToStatusCodeWhenBodyIsNotJson() {
        String message = UserApiClient.registerErrorMessage("not json at all", 422);

        assertTrue(message.contains("422"));
    }
}