package com.example.walking_meditation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.example.walking_meditation.utils.RatingScaleUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class RatingScaleUtilsTest {
    @Test
    public void validatesFourAndFivePointRanges() {
        RatingScaleUtils.validateRange(4, 0, 3);
        RatingScaleUtils.validateRange(4, 1, 4);
        RatingScaleUtils.validateRange(5, 0, 4);
        RatingScaleUtils.validateRange(5, 1, 5);
    }

    @Test
    public void rejectsRangeThatDoesNotMatchSelectedPointCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RatingScaleUtils.validateRange(4, 1, 5)
        );
    }

    @Test
    public void reusesExistingNumericScale() throws Exception {
        JSONObject definition = new JSONObject(
                "{\"scales\":{\"existing\":{\"min\":0,\"max\":3}}}"
        );

        String reference = RatingScaleUtils.findOrCreateNumericScale(definition, 0, 3);

        assertEquals("existing", reference);
        assertEquals(1, definition.getJSONObject("scales").length());
    }

    @Test
    public void createsAndThenReusesMissingScale() throws Exception {
        JSONObject definition = new JSONObject();

        String created = RatingScaleUtils.findOrCreateNumericScale(definition, 1, 4);
        String reused = RatingScaleUtils.findOrCreateNumericScale(definition, 1, 4);

        assertEquals("scale_1_4", created);
        assertEquals(created, reused);
        assertEquals(1, definition.getJSONObject("scales").length());
    }

    @Test
    public void doesNotReuseLabelledOptionsScale() throws Exception {
        JSONObject labelled = new JSONObject();
        labelled.put("min", 0);
        labelled.put("max", 3);
        labelled.put("options", new JSONArray().put(
                new JSONObject().put("value", 0).put("label", "Never")
        ));
        JSONObject scales = new JSONObject().put("labelled", labelled);
        JSONObject definition = new JSONObject().put("scales", scales);

        String reference = RatingScaleUtils.findOrCreateNumericScale(definition, 0, 3);

        assertFalse("labelled".equals(reference));
        assertEquals("scale_0_3", reference);
        assertEquals(2, scales.length());
    }
}
