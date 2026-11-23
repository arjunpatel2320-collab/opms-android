package com.opms.presence;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Gets one location fix from the phone (GPS and network together), without Google Play services.
 * Takes the first fix that is accurate to 50 m, otherwise the best one after a few seconds.
 */
final class LocationFetcher {
    interface Callback {
        void done(Location location, String error);
    }

    private static final long GOOD_ENOUGH_AFTER_MS = 8_000;
    private static final long GIVE_UP_AFTER_MS = 25_000;
    private static final float GOOD_ACCURACY_M = 50f;
    private static final long RECENT_MS = 2 * 60_000;

    private final LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final List<String> providers = new ArrayList<>();
    private Location best;
    private boolean finished;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { offer(location); }
        // Required on Android 8–10, where these have no default implementation.
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private LocationFetcher(Context context, Callback callback) {
        this.lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.callback = callback;
    }

    /** Call on the main thread, after location permission was granted. */
    static void fetch(Context context, boolean precise, Callback callback) {
        new LocationFetcher(context, callback).start(precise);
    }

    @SuppressLint("MissingPermission")
    private void start(boolean precise) {
        if (lm == null) { finish(null, "This phone can’t share its location."); return; }
        List<String> wanted = new ArrayList<>();
        if (precise) wanted.add(LocationManager.GPS_PROVIDER);
        wanted.add(LocationManager.NETWORK_PROVIDER);
        if (android.os.Build.VERSION.SDK_INT >= 31) wanted.add(LocationManager.FUSED_PROVIDER);
        for (String p : wanted) {
            try {
                if (lm.getAllProviders().contains(p) && lm.isProviderEnabled(p)) providers.add(p);
            } catch (RuntimeException ignored) { /* provider not usable */ }
        }
        if (providers.isEmpty()) {
            finish(null, "Location is turned off on this phone. Turn on Location, then try again.");
            return;
        }
        for (String p : providers) {
            try {
                lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper());
            } catch (RuntimeException ignored) { /* e.g. GPS refused with approximate location only */ }
        }
        handler.postDelayed(() -> { if (best != null) finish(best, null); }, GOOD_ENOUGH_AFTER_MS);
        handler.postDelayed(() -> {
            Location fallback = best != null ? best : recentLastKnown();
            if (fallback != null) finish(fallback, null);
            else finish(null, "Your location couldn’t be found. Try again, ideally near a window.");
        }, GIVE_UP_AFTER_MS);
    }

    private void offer(Location location) {
        if (finished || location == null) return;
        if (best == null || (location.hasAccuracy() && (!best.hasAccuracy() || location.getAccuracy() < best.getAccuracy()))) {
            best = location;
        }
        if (best.hasAccuracy() && best.getAccuracy() <= GOOD_ACCURACY_M) finish(best, null);
    }

    @SuppressLint("MissingPermission")
    private Location recentLastKnown() {
        Location newest = null;
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (newest == null || l.getTime() > newest.getTime())) newest = l;
            } catch (RuntimeException ignored) { /* not allowed for this provider */ }
        }
        if (newest != null && System.currentTimeMillis() - newest.getTime() <= RECENT_MS) return newest;
        return null;
    }

    private void finish(Location location, String error) {
        if (finished) return;
        finished = true;
        handler.removeCallbacksAndMessages(null);
        try { lm.removeUpdates(listener); } catch (RuntimeException ignored) { /* already removed */ }
        callback.done(location, error);
    }
}
