package com.example.walking_meditation.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;

public class ActivityRecognitionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if (ActivityRecognitionResult.hasResult(intent)) {

            ActivityRecognitionResult result =
                    ActivityRecognitionResult.extractResult(intent);

            DetectedActivity activity =
                    result.getMostProbableActivity();

            UserActivityMonitor
                    .getInstance(context)
                    .handleDetectedActivity(
                            activity.getType(),
                            activity.getConfidence()
                    );
        }
    }
}