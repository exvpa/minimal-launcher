package com.frame.home;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/** Current weather for Bucharest from Open-Meteo (free, no API key). */
final class Weather {
    static final String PLACE = "Bucharest";
    private static final String URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=44.4268&longitude=26.1025"
            + "&current=temperature_2m,weather_code,is_day&timezone=Europe%2FBucharest";

    static final class Report {
        final int temperature;
        final String icon;

        Report(int temperature, String icon) {
            this.temperature = temperature;
            this.icon = icon;
        }
    }

    interface Callback {
        void onReport(Report report);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    void fetch(final Callback callback) {
        new Thread(() -> {
            try {
                JSONObject current = new JSONObject(Http.get(URL)).getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                String icon = icon(current.getInt("weather_code"), current.optInt("is_day", 1) == 1);
                final Report report = new Report(temp, icon);
                main.post(() -> callback.onReport(report));
            } catch (Exception ignored) {
                // Offline: keep showing the last report
            }
        }, "weather").start();
    }

    /** Maps WMO weather codes to the glyph names in {@link Glyphs}. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "clear-day" : "clear-night";
        if (code <= 2) return day ? "partly-day" : "partly-night";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snow";
        if (code >= 95) return "storm";
        return "rain";
    }
}
