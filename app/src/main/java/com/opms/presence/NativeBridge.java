package com.opms.presence;

import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the employee page can ask the app for, as window.OPMSAndroid.
 * Slow requests (selfie, location) return a request id at once; the page then asks result(id) until the answer is ready.
 * (Polling instead of calling back into the page keeps this independent of the page's security policy.)
 */
final class NativeBridge {
    private final MainActivity activity;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, String> results = new ConcurrentHashMap<>();

    NativeBridge(MainActivity activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------ called by the page

    @JavascriptInterface
    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    /** Opens the camera for a selfie. Answer: {"photo": "data:image/jpeg;base64,..."} | {"cancelled": true} | {"error": "..."} */
    @JavascriptInterface
    public String takeSelfie() {
        String id = newId();
        activity.runOnUiThread(() -> activity.startSelfie(id));
        return id;
    }

    /** Finds the phone's location. Answer: {"location": {latitude, longitude, accuracy}} | {"error": "..."} */
    @JavascriptInterface
    public String getLocation() {
        String id = newId();
        activity.runOnUiThread(() -> activity.startLocation(id));
        return id;
    }

    /** The answer for a request id, or "" while it is still being worked on. */
    @JavascriptInterface
    public String result(String id) {
        String r = id == null ? null : results.remove(id);
        return r == null ? "" : r;
    }

    /**
     * Keep the employee marked present in the background (phone locked) while they are in.
     * Returns true when the app is taking care of the automatic updates.
     */
    @JavascriptInterface
    public boolean setPresence(boolean keepUpdated, int intervalMinutes) {
        return activity.setBackgroundPresence(keepUpdated, intervalMinutes);
    }

    @JavascriptInterface
    public boolean backgroundActive() {
        return PresenceService.isRunning();
    }

    /** Shows the "server address" screen. */
    @JavascriptInterface
    public void changeServer() {
        activity.runOnUiThread(activity::showSetupFromPage);
    }

    // ------------------------------------------------------------ used by the activity

    void resolve(String id, JSONObject answer) {
        if (id != null) results.put(id, answer.toString());
    }

    void resolveError(String id, String message) {
        resolve(id, json("error", message));
    }

    static JSONObject json(String key, Object value) {
        JSONObject o = new JSONObject();
        try { o.put(key, value); } catch (JSONException ignored) { /* key is never null */ }
        return o;
    }

    private String newId() {
        return "r" + nextId.getAndIncrement();
    }
}
