package com.frame.home;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/** Home screen activity. All drawing and remote handling lives in {@link HomeView}. */
public class HomeActivity extends Activity {
    private static final int REQUEST_LOCATION = 1;
    private HomeView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        view = new HomeView(this);
        // Aerial video (when on) plays under the home screen
        FrameLayout layers = new FrameLayout(this);
        layers.addView(view.aerialView());
        layers.addView(view);
        setContentView(layers);
        // Weather follows the TV's location; without it, Bucharest is used
        if (!Weather.hasPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_LOCATION && Weather.hasPermission(this)) view.refreshWeather();
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
