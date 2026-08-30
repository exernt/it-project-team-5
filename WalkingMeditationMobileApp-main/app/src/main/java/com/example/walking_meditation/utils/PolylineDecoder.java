package com.example.walking_meditation.utils;

import com.example.walking_meditation.models.LatLon;

import java.util.ArrayList;
import java.util.List;

public final class PolylineDecoder {
    private PolylineDecoder() {}

    public static List<LatLon> decode(String encoded) {
        List<LatLon> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return points;

        int index = 0;
        int latitude = 0;
        int longitude = 0;
        while (index < encoded.length()) {
            DecodeResult latResult = decodeValue(encoded, index);
            index = latResult.nextIndex;
            DecodeResult lonResult = decodeValue(encoded, index);
            index = lonResult.nextIndex;

            latitude += latResult.delta;
            longitude += lonResult.delta;
            points.add(new LatLon(latitude / 1e5, longitude / 1e5));
        }
        return points;
    }

    private static DecodeResult decodeValue(String encoded, int startIndex) {
        int result = 0;
        int shift = 0;
        int index = startIndex;
        int value;
        do {
            if (index >= encoded.length()) {
                throw new IllegalArgumentException("Truncated encoded polyline");
            }
            value = encoded.charAt(index++) - 63;
            result |= (value & 0x1f) << shift;
            shift += 5;
            if (shift > 30) throw new IllegalArgumentException("Invalid encoded polyline");
        } while (value >= 0x20);

        int delta = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
        return new DecodeResult(delta, index);
    }

    private static final class DecodeResult {
        final int delta;
        final int nextIndex;

        DecodeResult(int delta, int nextIndex) {
            this.delta = delta;
            this.nextIndex = nextIndex;
        }
    }
}
