package com.example.walking_meditation.utils;

import androidx.annotation.NonNull;

public final class ProfileInputValidator {
    private ProfileInputValidator() {}

    public static int requireValidAge(@NonNull String value) {
        final int age;
        try {
            age = Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter an age between 18 and 120");
        }
        if (age < 18 || age > 120) {
            throw new IllegalArgumentException("Enter an age between 18 and 120");
        }
        return age;
    }

    @NonNull
    public static String requireGender(
            boolean male,
            boolean female,
            boolean other,
            @NonNull String otherValue
    ) {
        int selections = (male ? 1 : 0) + (female ? 1 : 0) + (other ? 1 : 0);
        if (selections != 1) {
            throw new IllegalArgumentException("Select a gender");
        }
        if (male) return "Male";
        if (female) return "Female";
        String customGender = otherValue.trim();
        if (customGender.isEmpty()) {
            throw new IllegalArgumentException("Enter your gender");
        }
        if (customGender.length() > 100) {
            throw new IllegalArgumentException("Gender must be 100 characters or fewer");
        }
        return customGender;
    }
}
