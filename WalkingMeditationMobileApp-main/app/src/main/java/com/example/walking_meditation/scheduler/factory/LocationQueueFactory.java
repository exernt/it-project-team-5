package com.example.walking_meditation.scheduler.factory;

import android.content.Context;
import android.content.res.XmlResourceParser;

import com.example.walking_meditation.R;
import com.example.walking_meditation.scheduler.LocationAudio;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

public class LocationQueueFactory {

    private LocationQueueFactory() {
        // Prevent instantiation
    }

    public static List<LocationAudio> createFromNavigationXml(Context context) {

        List<LocationAudio> queue = new ArrayList<>();

        XmlResourceParser parser =
                context.getResources().getXml(R.xml.navigation);

        try {
            int eventType = parser.getEventType();

            while (eventType != XmlPullParser.END_DOCUMENT) {

                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("navigation")) {
                    String latStr = parser.getAttributeValue(null, "lat");
                    String lonStr = parser.getAttributeValue(null, "lon");
                    String clipName = parser.getAttributeValue(null, "clip");

                    if (latStr != null && lonStr != null && clipName != null) {

                        double lat = Double.parseDouble(latStr);
                        double lon = Double.parseDouble(lonStr);

                        // Remove extension (segment_1.mp3 → segment_1)
                        String resourceName =
                                clipName.replace(".mp3", "").toLowerCase();

                        int resId = context.getResources().getIdentifier(resourceName, "raw", context.getPackageName());

                        if (resId != 0) {
                            queue.add(new LocationAudio(lat, lon, resId));
                        }
                    }
                }

                eventType = parser.next();
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            parser.close();
        }

        return queue;
    }
}