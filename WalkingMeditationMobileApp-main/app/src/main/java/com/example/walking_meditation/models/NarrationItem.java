package com.example.walking_meditation.models;

public class NarrationItem {

    public enum Status {
        READY,
        DOWNLOADING,
        DOWNLOADED,
        ERROR
    }

    public final String id;
    public final String text;

    public Status status = Status.READY;
    public int progress = 0; // 0..100
    public String localFilePath = null;

    public NarrationItem(String id, String text) {
        this.id = id;
        this.text = text;
    }
}