package com.example.walking_meditation;

import com.example.walking_meditation.models.LatLon;
import com.example.walking_meditation.utils.PolylineDecoder;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PolylineDecoderTest {
    @Test
    public void decodesGoogleReferencePolyline() {
        List<LatLon> points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");

        assertEquals(3, points.size());
        assertEquals(38.5, points.get(0).lat, 0.00001);
        assertEquals(-120.2, points.get(0).lon, 0.00001);
        assertEquals(43.252, points.get(2).lat, 0.00001);
        assertEquals(-126.453, points.get(2).lon, 0.00001);
    }

    @Test
    public void rejectsTruncatedPolyline() {
        assertThrows(IllegalArgumentException.class, () -> PolylineDecoder.decode("_p~iF"));
    }
}
