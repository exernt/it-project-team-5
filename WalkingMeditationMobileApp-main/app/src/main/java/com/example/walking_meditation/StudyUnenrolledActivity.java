package com.example.walking_meditation;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class StudyUnenrolledActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_unenrolled);
        setTitle(R.string.manage_study);
    }
}