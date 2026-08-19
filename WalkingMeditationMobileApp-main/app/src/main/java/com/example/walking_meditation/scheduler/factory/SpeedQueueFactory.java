package com.example.walking_meditation.scheduler.factory;

import com.example.walking_meditation.R;
import com.example.walking_meditation.scheduler.SpeedAudioQueue;


public class SpeedQueueFactory {

    private SpeedQueueFactory() {
        // Prevent instantiation
    }

    public static SpeedAudioQueue createMeditationSpeedProfile() {

        // Target speed = 5 km/h
        // Tolerance = 0.5 km/h
        SpeedAudioQueue queue =
                new SpeedAudioQueue(5.0f, 0.5f);

        // BELOW SPEED
//        queue.addBelowClip(R.raw.slow_clip1);
//        queue.addBelowClip(R.raw.slow_clip2);
//
//        // ON SPEED
//        queue.addOnClip(R.raw.good_pace1);
//        queue.addOnClip(R.raw.good_pace2);
//
//        // ABOVE SPEED
//        queue.addAboveClip(R.raw.fast_clip1);
//        queue.addAboveClip(R.raw.fast_clip2);

        return queue;
    }

    public static SpeedAudioQueue createSlowMindfulnessProfile() {

        SpeedAudioQueue queue =
                new SpeedAudioQueue(3.0f, 0.3f);

//        queue.addBelowClip(R.raw.very_slow_clip1);
//        queue.addOnClip(R.raw.slow_good_clip1);
//        queue.addAboveClip(R.raw.speed_up_gently_clip1);

        return queue;
    }
}