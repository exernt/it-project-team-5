package com.example.walking_meditation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.example.walking_meditation.utils.ProfileInputValidator;

import org.junit.Test;

public class ProfileInputValidatorTest {
    @Test
    public void ageMustBeBetween18And120Inclusive() {
        assertEquals(18, ProfileInputValidator.requireValidAge("18"));
        assertEquals(120, ProfileInputValidator.requireValidAge("120"));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileInputValidator.requireValidAge("17"));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileInputValidator.requireValidAge("121"));
    }

    @Test
    public void genderSelectionIsExclusiveAndOtherRequiresText() {
        assertEquals("Male", ProfileInputValidator.requireGender(true, false, false, ""));
        assertEquals("Female", ProfileInputValidator.requireGender(false, true, false, ""));
        assertEquals("Non-binary",
                ProfileInputValidator.requireGender(false, false, true, " Non-binary "));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileInputValidator.requireGender(false, false, false, ""));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileInputValidator.requireGender(false, false, true, ""));
    }
}
