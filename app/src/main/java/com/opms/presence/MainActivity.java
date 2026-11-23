package com.opms.presence;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The OPMS employee app: the employee portal from the office's OPMS server, plus the phone's own camera,
 * location and a background service that keeps the employee marked present while the phone is locked.
 */
public class MainActivity extends Activity {
    private static final int REQ_SELFIE = 10;
    private static final int REQ_LOCATION = 11;
    private static final int REQ_NOTIFICATIONS = 12;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private WebView web;
    private ProgressBar progress;
    private View setupPanel;
    private View errorPanel;
    private EditText serverInput;
    private TextView setupStatus;
    private TextView errorText;
    private Button connectButton;

    private NativeBridge bridge;
    private String base;               // e.g. http://192.168.1.10:4000
    private boolean pageFailed;

    private String pendingSelfieId;
    private File selfieFile;
    private String pendingLocationId;
    private boolean askedNotifications;

    // ------------------------------------------------------------ start

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        setupPanel = findViewById(R.id.setup_panel);
        errorPanel = findViewById(R.id.error_panel);
        serverInput = findViewById(R.id.server_input);
        setupStatus = findViewById(R.id.setup_status);
        errorText = findViewById(R.id.error_text);
        connectButton = findViewById(R.id.connect);

        connectButton.setOnClickListener(v -> connect());
        serverInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) { connect(); return true; }
            return false;
        });
        findViewById(R.id.retry).setOnClickListener(v -> loadPortal());
        findViewById(R.id.change_server).setOnClickListener(v -> showSetup(null));

        setUpWebView();

        base = ServerConfig.get(this);
        if (base == null) showSetup(null);
        else if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) loadPortal();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setUpWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // the page remembers small choices (like the auto-update switch)
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // The server recognises the app by this ("OPMS-Android") and records it as the source.
        s.setUserAgentString(s.getUserAgentString() + " OPMS-Android/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptCookie(true);

        bridge = new NativeBridge(this);
        web.addJavascriptInterface(bridge, "OPMSAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (ServerConfig.sameOrigin(base, url)) return false;
                // Anything else (for example a map link) opens in the phone's browser or maps app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) { /* nothing can open it */ }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageFailed = false;
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!pageFailed) errorPanel.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("The app couldn’t open " + base + ". Check that this phone is on the office Wi-Fi and that the OPMS server is running.");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showError("The server at " + base + " answered with an error (" + response.getStatusCode()
                            + "). Check the server address, or ask your administrator.");
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }
        });
    }

    private void loadPortal() {
        if (base == null) { showSetup(null); return; }
        errorPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(ServerConfig.portalUrl(base));
    }

    private void showError(String message) {
        pageFailed = true;
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ server address

    void showSetupFromPage() {
        showSetup(null);
    }

    private void showSetup(String message) {
        errorPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.VISIBLE);
        String saved = ServerConfig.get(this);
        if (saved != null && serverInput.getText().length() == 0) {
            serverInput.setText(saved.replaceFirst("^http://", ""));
        }
        setStatus(message, true);
        connectButton.setEnabled(true);
    }

    private void setStatus(String message, boolean isError) {
        setupStatus.setVisibility(message == null ? View.GONE : View.VISIBLE);
        setupStatus.setText(message == null ? "" : message);
        setupStatus.setTextColor(getColor(isError ? R.color.danger : R.color.muted));
    }

    private void connect() {
        String candidate = ServerConfig.normalize(serverInput.getText().toString());
        if (candidate == null) {
            setStatus("Enter the server address, for example 192.168.1.10:4000", true);
            return;
        }
        hideKeyboard();
        connectButton.setEnabled(false);
        setStatus(getString(R.string.setup_checking), false);
        worker.execute(() -> {
            boolean ok = ServerConfig.ping(candidate);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                connectButton.setEnabled(true);
                if (!ok) {
                    setStatus("No OPMS server answered at " + candidate + ". Check the address (with the port, like :4000), "
                            + "that the server is running, and that this phone is on the office Wi-Fi.", true);
                    return;
                }
                boolean changed = !candidate.equals(base);
                if (changed) PresenceService.stop(this); // the old server's background updates end
                base = candidate;
                ServerConfig.save(this, candidate);
                setStatus(null, false);
                if (changed) web.clearHistory();
                loadPortal();
            });
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(serverInput.getWindowToken(), 0);
    }

    // ------------------------------------------------------------ selfie (the phone's camera app)

    void startSelfie(String id) {
        if (pendingSelfieId != null) bridge.resolve(pendingSelfieId, NativeBridge.json("cancelled", true));
        pendingSelfieId = null;
        try {
            File dir = new File(getCacheDir(), "selfies");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            selfieFile = new File(dir, "selfie.jpg");
            //noinspection ResultOfMethodCallIgnored
            selfieFile.delete();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", selfieFile);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            // Ask for the front camera (camera apps treat these as hints).
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1);
            intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1);
            intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pendingSelfieId = id;
            startActivityForResult(intent, REQ_SELFIE);
        } catch (ActivityNotFoundException e) {
            pendingSelfieId = null;
            bridge.resolveError(id, "No camera app was found on this phone.");
        } catch (RuntimeException e) {
            pendingSelfieId = null;
            bridge.resolveError(id, "The camera couldn’t be opened. Please try again.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SELFIE) return;
        String id = pendingSelfieId;
        pendingSelfieId = null;
        if (id == null) return;
        if (resultCode != RESULT_OK) {
            bridge.resolve(id, NativeBridge.json("cancelled", true));
            return;
        }
        File file = selfieFile;
        worker.execute(() -> {
            try {
                bridge.resolve(id, NativeBridge.json("photo", SelfieProcessor.toDataUrl(file)));
            } catch (Exception | OutOfMemoryError e) {
                bridge.resolveError(id, "The photo couldn’t be used. Please take it again.");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (file != null) file.delete();
            }
        });
    }

    // ------------------------------------------------------------ location

    void startLocation(String id) {
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (fine || coarse) { fetchLocation(id, fine); return; }
        if (pendingLocationId != null) bridge.resolveError(pendingLocationId, "Location request replaced.");
        pendingLocationId = id;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    private void fetchLocation(String id, boolean precise) {
        LocationFetcher.fetch(this, precise, (Location loc, String error) -> {
            if (loc == null) { bridge.resolveError(id, error); return; }
            try {
                JSONObject l = new JSONObject();
                l.put("latitude", loc.getLatitude());
                l.put("longitude", loc.getLongitude());
                if (loc.hasAccuracy()) l.put("accuracy", (double) loc.getAccuracy());
                bridge.resolve(id, NativeBridge.json("location", l));
            } catch (JSONException e) {
                bridge.resolveError(id, "Your location couldn’t be read. Please try again.");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        String id = pendingLocationId;
        pendingLocationId = null;
        if (id == null) return;
        boolean fine = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (fine || coarse) fetchLocation(id, fine);
        else bridge.resolveError(id, "Location access is off for OPMS. Allow it in Settings → Apps → OPMS → Permissions → Location, then try again.");
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------ background presence

    /** Called by the page (on the bridge thread). True when the background service is taking care of updates. */
    boolean setBackgroundPresence(boolean keepUpdated, int intervalMinutes) {
        String server = base;
        if (!keepUpdated || server == null) {
            PresenceService.stop(this);
            return false;
        }
        if (PresenceService.isRunningFor(server, intervalMinutes)) return true;
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            askedNotifications = true;
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS));
        }
        return PresenceService.start(this, server, intervalMinutes);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (setupPanel.getVisibility() == View.VISIBLE && base != null) { loadPortal(); return; }
        if (web.getVisibility() == View.VISIBLE && errorPanel.getVisibility() != View.VISIBLE && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush(); // the background service reads the sign-in cookie
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        web.removeJavascriptInterface("OPMSAndroid");
        web.destroy();
        super.onDestroy();
    }
}
