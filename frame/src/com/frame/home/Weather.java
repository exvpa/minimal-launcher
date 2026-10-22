package com.frame.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Current weather from Open-Meteo (free, no API key) for the TV's location,
 * as known to Android's location services (Wi-Fi based on a TV). Falls back
 * to Bucharest without the permission or a location fix.
 */
final class Weather {
    private static final double FALLBACK_LAT = 44.4268, FALLBACK_LON = 26.1025;
    private static final String FALLBACK_PLACE = "Bucharest";
    private static final long STALE_MS = 60 * 60 * 1000L;

    static final class Report {
        final int temperature;
        final String icon;
        final String place;

        Report(int temperature, String icon, String place) {
            this.temperature = temperature;
            this.icon = icon;
            this.place = place;
        }
    }

    interface Callback {
        void onReport(Report report);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private double placeLat = Double.NaN, placeLon = Double.NaN;
    private String placeName;

    Weather(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void fetch(final Callback callback) {
        final Location location = lastLocation();
        new Thread(() -> {
            try {
                double lat = FALLBACK_LAT, lon = FALLBACK_LON;
                String place = FALLBACK_PLACE;
                if (location != null) {
                    lat = location.getLatitude();
                    lon = location.getLongitude();
                    place = placeName(lat, lon);
                }
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon);
                JSONObject current = new JSONObject(Http.get(url)).getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                String icon = icon(current.getInt("weather_code"), current.optInt("is_day", 1) == 1);
                final Report report = new Report(temp, icon, place);
                main.post(() -> callback.onReport(report));
            } catch (Exception ignored) {
                // Offline: keep showing the last report
            }
        }, "weather").start();
    }

    /** Newest last-known fix from any provider; asks for a fresh one in the background when it's old. */
    private Location lastLocation() {
        if (!hasPermission(context)) return null;
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            boolean stale = best == null || System.currentTimeMillis() - best.getTime() > STALE_MS;
            if (stale && Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Warms the cache; the next weather refresh picks it up
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, context.getMainExecutor(), l -> { });
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            return null;
        }
        return best;
    }

    /** Town name for the coordinates, looked up again only when the TV has moved more than ~1 km. */
    private String placeName(double lat, double lon) {
        if (placeName != null && Math.abs(lat - placeLat) < 0.01 && Math.abs(lon - placeLon) < 0.01) return placeName;
        String name = "";
        try {
            if (Geocoder.isPresent()) {
                List<Address> found = new Geocoder(context, Locale.ENGLISH).getFromLocation(lat, lon, 1);
                if (found != null && !found.isEmpty()) {
                    Address a = found.get(0);
                    name = a.getLocality() != null ? a.getLocality()
                            : a.getSubAdminArea() != null ? a.getSubAdminArea()
                            : a.getAdminArea() != null ? a.getAdminArea() : "";
                }
            }
        } catch (Exception ignored) {
            // No geocoder backend: show the temperature without a name
        }
        placeLat = lat;
        placeLon = lon;
        placeName = name;
        return name;
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
