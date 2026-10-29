package com.example.walking_meditation.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.MapView;

/** A MapView that keeps ancestor scrolling disabled for the duration of a map gesture. */
public final class ScrollLockMapView extends MapView {

    public ScrollLockMapView(@NonNull Context context) {
        super(context);
    }

    public ScrollLockMapView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ScrollLockMapView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent event) {
        ViewParent parent = getParent();
        int action = event.getActionMasked();
        if (parent != null && action == MotionEvent.ACTION_DOWN) {
            parent.requestDisallowInterceptTouchEvent(true);
        }

        boolean handled = super.dispatchTouchEvent(event);

        if (parent != null
                && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }
}
