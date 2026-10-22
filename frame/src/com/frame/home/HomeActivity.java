package com.frame.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;

/** Home screen activity. All drawing and remote handling lives in {@link HomeView}. */
public class HomeActivity extends Activity {
    private HomeView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        view = new HomeView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    @Override
    protected void onPause() {
        view.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first app
        view.reset();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return view.handleKey(event) || super.dispatchKeyEvent(event);
    }
}
