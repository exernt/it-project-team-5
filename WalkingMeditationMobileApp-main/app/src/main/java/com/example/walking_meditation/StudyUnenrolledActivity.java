package com.example.walking_meditation;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class StudyUnenrolledActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_unenrolled);
        setTitle(R.string.manage_study);

        findViewById(R.id.btnViewOpenStudies).setOnClickListener(view ->
                startActivity(new Intent(this, StudyViewListActivity.class))
        );
    }
}
