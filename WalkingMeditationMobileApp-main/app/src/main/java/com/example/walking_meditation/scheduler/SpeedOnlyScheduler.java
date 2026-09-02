package com.example.walking_meditation.scheduler;

import android.content.Context;
import android.location.Location;
import android.media.MediaPlayer;

public class SpeedOnlyScheduler implements AudioScheduler {

    private static final long COOLDOWN_MS = 20_000L;

    private final Context context;
    private final SpeedAudioQueue queue;

    private MediaPlayer mediaPlayer;
    private long lastPlayedTime = 0L;
    private boolean resumePlayback = false;

    public SpeedOnlyScheduler(Context context,
                              SpeedAudioQueue queue) {
        this.context = context;
        this.queue = queue;
    }

    @Override
    public void onLocationUpdate(Location location, float speedKmph) {

        long now = System.currentTimeMillis();

        if (now - lastPlayedTime < COOLDOWN_MS) return;

        int clip = -1;

        float target = queue.getTargetSpeed();
        float tolerance = queue.getTolerance();

        if (speedKmph < target - tolerance) {
            clip = queue.getRandomBelow();
        }
        else if (speedKmph > target + tolerance) {
            clip = queue.getRandomAbove();
        }
        else {
            clip = queue.getRandomOn();
        }

        if (clip != -1) {
            play(clip);
            lastPlayedTime = now;
        }
    }

    @Override
    public void resume() {
        if (resumePlayback && mediaPlayer != null && !mediaPlayer.isPlaying()){
            mediaPlayer.start();
        }
        resumePlayback = false;
    }

    @Override
    public void pause() {
        if(mediaPlayer != null && mediaPlayer.isPlaying()){
            resumePlayback = true;
            mediaPlayer.pause();
        }
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        resumePlayback = false;
    }

    private void play(int resId) {

        if (mediaPlayer != null) {
            mediaPlayer.release();
        }

        mediaPlayer = MediaPlayer.create(context, resId);

        if (mediaPlayer != null) {
            mediaPlayer.start();
        }
    }
}
