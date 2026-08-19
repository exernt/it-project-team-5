package com.example.walking_meditation.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SpeedAudioQueue {

    private final List<Integer> belowSpeedClips = new ArrayList<>();
    private final List<Integer> onSpeedClips = new ArrayList<>();
    private final List<Integer> aboveSpeedClips = new ArrayList<>();

    private final float targetSpeed;
    private final float tolerance;
    private final Random random = new Random();

    public SpeedAudioQueue(float targetSpeed, float tolerance) {
        this.targetSpeed = targetSpeed;
        this.tolerance = tolerance;
    }

    public void addBelowClip(int resId) {
        belowSpeedClips.add(resId);
    }

    public void addOnClip(int resId) {
        onSpeedClips.add(resId);
    }

    public void addAboveClip(int resId) {
        aboveSpeedClips.add(resId);
    }

    public float getTargetSpeed() {
        return targetSpeed;
    }

    public float getTolerance() {
        return tolerance;
    }

    public int getRandomBelow() {
        return getRandom(belowSpeedClips);
    }

    public int getRandomOn() {
        return getRandom(onSpeedClips);
    }

    public int getRandomAbove() {
        return getRandom(aboveSpeedClips);
    }

    private int getRandom(List<Integer> list) {
        if (list.isEmpty()) return -1;
        return list.get(random.nextInt(list.size()));
    }
}