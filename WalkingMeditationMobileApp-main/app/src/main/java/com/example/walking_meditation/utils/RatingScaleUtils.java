package com.example.walking_meditation.utils;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public final class RatingScaleUtils {
    private RatingScaleUtils() {}

    public static void validateRange(int pointCount, int min, int max) {
        if (pointCount != 4 && pointCount != 5) {
            throw new IllegalArgumentException("Scale must have either 4 or 5 points");
        }
        long actualPointCount = (long) max - min + 1;
        if (actualPointCount != pointCount) {
            throw new IllegalArgumentException(
                    "A " + pointCount + "-point scale must contain " + pointCount
                            + " values from start to end"
            );
        }
    }

    @NonNull
    public static String findOrCreateNumericScale(
            @NonNull JSONObject definition,
            int min,
            int max
    ) throws JSONException {
        JSONObject scales = definition.optJSONObject("scales");
        if (scales == null) {
            scales = new JSONObject();
            definition.put("scales", scales);
        }

        Iterator<String> references = scales.keys();
        while (references.hasNext()) {
            String reference = references.next();
            JSONObject scale = scales.optJSONObject(reference);
            if (isNumericScale(scale)
                    && scale.getInt("min") == min
                    && scale.getInt("max") == max) {
                return reference;
            }
        }

        String baseReference = "scale_" + min + "_" + max;
        String reference = baseReference;
        int suffix = 2;
        while (scales.has(reference)) {
            reference = baseReference + "_" + suffix;
            suffix++;
        }

        JSONObject scale = new JSONObject();
        scale.put("min", min);
        scale.put("max", max);
        scales.put(reference, scale);
        return reference;
    }

    public static boolean isNumericScale(JSONObject scale) {
        return scale != null
                && !scale.has("options")
                && scale.opt("min") instanceof Number
                && scale.opt("max") instanceof Number;
    }
}
