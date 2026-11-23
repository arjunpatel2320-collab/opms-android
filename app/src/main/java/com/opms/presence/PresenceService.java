package com.opms.presence;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the employee marked present while the phone is locked: every few minutes (the server's update interval)
 * it sends the same automatic update the open web page would send. A notification shows while it runs.
 *
 * It only runs between an accepted arrival selfie and leaving, and it stops by itself when the server says the
 * employee is no longer "in" (left, a new day, signed out) or after 14 hours.
 * Updates only count from the office network, exactly as they do on the web page.
 */
public class PresenceService extends Service {
    private static final String EXTRA_BASE = "base";
    private static final String EXTRA_INTERVAL = "interval";
    private static final String CHANNEL = "presence";
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTICE_ID = 2;
    private static final long MAX_RUN_MS = 14L * 60 * 60 * 1000;
    private static final long RETRY_MS = 60_000;

    private static volatile boolean running;
    private static volatile String runningBase;
    private static volatile int runningInterval;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private String base;
    private int intervalMinutes = 5;
    private long startedAt;

    // ------------------------------------------------------------ control (from the activity)

    static boolean isRunning() {
        return running;
    }

    static boolean isRunningFor(String base, int intervalMinutes) {
        return running && base.equals(runningBase) && runningInterval == clampInterval(intervalMinutes);
    }

    /** Starts (or updates) the service. Returns false if Android refused, e.g. because the app is in the background. */
    static boolean start(Context context, String base, int intervalMinutes) {
        Intent i = new Intent(context, PresenceService.class)
                .putExtra(EXTRA_BASE, base)
                .putExtra(EXTRA_INTERVAL, clampInterval(intervalMinutes));
        try {
            context.startForegroundService(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void stop(Context context) {
        if (!running) return;
        context.stopService(new Intent(context, PresenceService.class));
    }

    private static int clampInterval(int minutes) {
        return Math.max(1, Math.min(60, minutes <= 0 ? 5 : minutes));
    }

    // ------------------------------------------------------------ service

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        thread = new HandlerThread("opms-presence");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opms:presence");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires the notification straight away.
        goForeground(notification("Keeping you marked as present."));
        if (intent == null || intent.getStringExtra(EXTRA_BASE) == null) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        String newBase = intent.getStringExtra(EXTRA_BASE);
        int newInterval = clampInterval(intent.getIntExtra(EXTRA_INTERVAL, 5));
        boolean restart = !running || !newBase.equals(base) || newInterval != intervalMinutes;
        base = newBase;
        intervalMinutes = newInterval;
        runningBase = newBase;
        runningInterval = newInterval;
        if (!running) {
            running = true;
            startedAt = System.currentTimeMillis();
            wakeLock.acquire(MAX_RUN_MS + 60_000);
        }
        if (restart) {
            handler.removeCallbacksAndMessages(null);
            handler.post(beat);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        runningBase = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ the automatic update

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (System.currentTimeMillis() - startedAt > MAX_RUN_MS) {
                notice("Stopped keeping you marked present after 14 hours. Open OPMS if you’re still at work.");
                stopSelf();
                return;
            }
            long delay = sendUpdate();
            if (delay < 0) { stopSelf(); return; }
            handler.postDelayed(this, delay);
        }
    };

    /** Sends one update. Returns the wait before the next one, or -1 to stop. */
    private long sendUpdate() {
        long interval = intervalMinutes * 60_000L;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/api/employee/presence").openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "OPMS-Android/" + BuildConfig.VERSION_NAME + " (background)");
            String cookie = signInCookie();
            if (cookie == null || cookie.isEmpty()) {
                notice("You’re signed out of OPMS, so automatic updates stopped. Open the app to sign in.");
                return -1;
            }
            c.setRequestProperty("Cookie", cookie);
            try (OutputStream out = c.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = ServerConfig.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            JSONObject json = parse(body);
            String error = json.optString("error", "");

            if (code >= 200 && code < 300) {
                JSONObject settings = json.optJSONObject("settings");
                if (settings != null) {
                    int serverInterval = settings.optInt("heartbeatIntervalMinutes", intervalMinutes);
                    intervalMinutes = clampInterval(serverInterval);
                    runningInterval = intervalMinutes;
                    interval = intervalMinutes * 60_000L;
                }
                update("Present · last update " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
                return interval;
            }
            if (code == 401) {
                notice("Your OPMS sign-in ended, so automatic updates stopped. Open the app to sign in again.");
                return -1;
            }
            if (code == 409 && "arrival_required".equals(error)) return -1;  // left, or a new day
            if (code == 403 && ("setup_required".equals(error) || "account_disabled".equals(error))) return -1;
            if (code == 403 && "not_on_office_network".equals(error)) {
                update("Not on the office Wi-Fi. Trying again in " + intervalMinutes + " min.");
                return interval;
            }
            update("The server couldn’t record the update. Trying again soon.");
            return Math.min(interval, 2 * RETRY_MS);
        } catch (IOException | RuntimeException e) {
            update("Can’t reach the OPMS server. Trying again in a minute.");
            return RETRY_MS;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The WebView's sign-in cookie for the server (read on the main thread, where WebView classes are safest). */
    private String signInCookie() {
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                value.set(CookieManager.getInstance().getCookie(base));
            } catch (RuntimeException ignored) {
                // leave it empty
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return value.get();
    }

    private static JSONObject parse(String body) {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------ notifications

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_presence)
                .setContentTitle("At work")
                .setContentText(text)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void update(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && running) nm.notify(NOTIFICATION_ID, notification(text));
    }

    /** A one-off message that stays after the service has stopped. */
    private void notice(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_presence)
                .setContentTitle("OPMS")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
        nm.notify(NOTICE_ID, n);
    }
}
