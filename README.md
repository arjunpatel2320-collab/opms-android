# OPMS – Android app

The employee app for the **Office Presence Monitoring System (OPMS)**. It opens the OPMS employee portal from your office's OPMS server and adds what a phone browser can't do over a plain `http://` address:

- **Selfies** with the phone's camera when arriving and leaving
- **Location (GPS)** at the moment of each selfie
- **Stays marked present with the phone locked.** After an accepted arrival selfie, the app sends the automatic update every few minutes and shows an "At work" notification. It stops when the employee records leaving, signs out, or after 14 hours.

The face check, the office-network check and all records stay on the OPMS server, exactly as on the web.

| | |
|---|---|
| Android version | 8.0 or newer |
| Server | OPMS server version 3.1 or newer, on the same Wi-Fi as the phones |
| Permissions | Location (asked at the first selfie), notifications (Android 13+, asked when background updates start). The camera is used through the phone's camera app, so no camera permission is needed. |

---

## 1. Build the APK on GitHub

GitHub builds the app for you. You don't need Android Studio.

1. Create a new repository on GitHub (for example `opms-android`).
2. Click **uploading an existing file**, then drag **everything inside** the `opms-android` folder into the page, including the `.github` folder. Click **Commit changes**.
3. Open the **Actions** tab. The **Build APK** run starts by itself and takes about 5 minutes.
4. When it shows a green tick, open the run. Under **Artifacts**, download **OPMS-1.0.x**. It's a zip file with the APK inside.

If the Actions tab shows no workflow, the `.github` folder didn't upload. Use **Add file → Create new file**, name it `.github/workflows/build-apk.yml`, paste in the contents of that file from this project, and commit.

### Publish a download link (Releases)

1. On the repository page, go to **Releases → Draft a new release**.
2. Under **Choose a tag**, type `v1.0.0` and pick **Create new tag**. Give it a title and click **Publish release**.
3. About 5 minutes later, **OPMS-1.0.0.apk** appears under that release's **Assets**. Share that link with your employees.

For an update, publish a new release with a higher number (`v1.0.1`, `v1.1.0` and so on).

## 2. Signing key (recommended, one time)

Android only installs an update over an app signed with the same key. Add your key as four GitHub secrets so every build uses it:

**Settings → Secrets and variables → Actions → New repository secret.** Add each name and value from `android-signing-secrets.txt`:

- `OPMS_KEYSTORE_BASE64`
- `OPMS_KEYSTORE_PASSWORD`
- `OPMS_KEY_ALIAS`
- `OPMS_KEY_PASSWORD`

Then run the build again: **Actions → Build APK → Run workflow**.

Without these secrets the app still builds, but with a temporary key that changes every build. Each update then needs the old app uninstalled first, and the build shows a warning.

**Never upload `android-signing-secrets.txt` or any `.p12` file to the repository.**

## 3. Install on a phone

1. Open the APK on the phone (from the Release link, or copy it over).
2. If Android asks, allow **Install unknown apps** for your browser or file manager.
3. If Play Protect warns about an unknown app, choose **Install anyway**. This is normal for apps that aren't on the Play Store.

## 4. First start

1. Connect the phone to the **office Wi-Fi**.
2. Open **OPMS** and enter the server address, for example `192.168.1.10:4000`. The OPMS server prints this address when it starts.
3. Sign in with the employee ID and password, as on the web.

To change the server address later, use **Change server address** on the sign-in screen, or on the "Can't reach the server" screen.

## How it decides who is present

| Step | Where it happens |
|---|---|
| Selfie and GPS taken | Phone (this app) |
| Phone is on the office network | Server checks the request's network address |
| Selfie matches the profile photo | Server (face check) |
| Automatic update every few minutes while "in" | Phone (background service) → server |
| Possibly away / Not detected | Server, when updates stop arriving (e.g. the phone left the office Wi-Fi) |

## Good to know

- **Some phones stop background apps** to save battery, especially Xiaomi, Oppo, Vivo, Realme and Samsung. If an employee shows "Possibly away" while at their desk, set **Settings → Apps → OPMS → Battery** to **Unrestricted** (or "No restrictions").
- The app uses plain `http://` on the office network. If the server is later given an `https://` address, just enter that instead.
- Liveness isn't checked: a printed photo could pass the face check. Every selfie is kept so the administrator can review it.

## Project layout

```
.github/workflows/build-apk.yml   GitHub build (APK as artifact, and on Releases)
app/src/main/java/com/opms/presence/
  MainActivity.java      the portal (WebView), server address screen, camera, location
  NativeBridge.java      what the portal page can ask the app for (window.OPMSAndroid)
  PresenceService.java   background updates while the employee is "in"
  LocationFetcher.java   one GPS/network location fix
  SelfieProcessor.java   shrinks and straightens the camera photo (max 800 px JPEG)
  ServerConfig.java      saved server address and the connection check
```

Building on a computer instead: install Android Studio, open this folder, and use **Build → Build APK(s)**.
