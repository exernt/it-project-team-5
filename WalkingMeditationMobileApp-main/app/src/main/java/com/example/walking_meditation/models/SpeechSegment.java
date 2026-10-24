package com.example.walking_meditation.models;

import java.io.File;

public class SpeechSegment {
    public String id;
    public String blockId;
    public String text;

    public boolean isPause;
    public long pauseDurationMs;

    public boolean ready;
    public boolean downloading;
    public File audioFile;
}