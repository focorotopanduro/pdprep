# PD Prep — Floating Overlay for Android

Native Android app. A floating, draggable, semi-transparent bubble stays on top of **Zoom, Teams, or any call app**. Tap the bubble to expand into a cheat-sheet panel. Tap a question → model answer appears. Tap 🔊 → your phone speaks the answer. Long-press the bubble → everything disappears.

Optimized for old Android phones: ~300 KB APK, minSdk 21 (Android 5.0 Lollipop, 2014), zero third-party libraries.

---

## 🟢 Easiest install path — cloud build, no PC software

See **[INSTALL.md](INSTALL.md)** — upload this folder to a free GitHub account, the APK is built in the cloud in ~3 minutes, download it straight to your phone. No Android Studio, no JDK, no local tooling.

---

## 🔵 Alternative — build locally with Android Studio (~30 min one-time setup)

### 1. Install Android Studio
- Download free from https://developer.android.com/studio
- Run the installer, accept defaults. It bundles its own JDK — nothing else to install.

### 2. Open this project
- Launch Android Studio
- **File → Open** → navigate to `C:\Users\Owner\Desktop\PDPrep` → OK
- First launch will say "Gradle sync". Wait 3–10 minutes while it downloads dependencies (one-time only; cached after).
- If it asks about a Gradle wrapper, say **Use Gradle wrapper**. If it asks to update AGP, say **No** (versions are pinned in this project).

### 3. Build the APK
- Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- Wait ~30–60 seconds on first build
- Bottom-right toast: **"APK generated successfully"** → click **"locate"**
- The file lives at:
  `C:\Users\Owner\Desktop\PDPrep\app\build\outputs\apk\debug\app-debug.apk`

---

## Install on your Android phone

### 1. Transfer the APK
- USB cable, email, or Google Drive — whichever is easiest
- Save `app-debug.apk` to your phone's Downloads folder

### 2. Enable "Install from unknown sources"
- Settings → **Security** (or Apps → Special access → Install unknown apps)
- Find the app you're using to open the APK (Files / Chrome / My Files) → **Allow**

### 3. Install
- Open the APK from your Files app → tap **Install** → **Open**

### 4. Grant overlay permission
- App opens showing "Step 1: Grant 'Display over other apps' permission"
- Tap **Grant Permission** → opens Settings
- Toggle **PD Prep** → ON → back button

### 5. Launch the bubble
- Tap **Start Floating Bubble**
- Small gold bubble appears on the right edge
- Open Zoom / Teams / any call app — bubble stays on top
- **Tap bubble** → cheat-sheet panel expands
- **Drag bubble** → move it anywhere
- **Long-press bubble (~1 sec)** → stop overlay completely

---

## Inside the panel

- **Drag bar at top** → move the panel
- **Eye icon** → cycle transparency: 96% → 75% → 55% (see the call underneath)
- **X icon** → collapse back to bubble
- Content is the same as your HTML app — Cheat Sheet + Interview Prep with 🔊 Listen buttons on every model answer

---

## Troubleshooting

**"App not installed" error on phone:** you have a conflicting version — uninstall the old PD Prep first.

**Bubble doesn't appear after tapping Start:** overlay permission isn't granted. Go to Settings → Apps → PD Prep → **Display over other apps** → ON.

**Bubble disappears when phone screen locks:** Android battery optimization killed the service. Settings → Apps → PD Prep → Battery → **Unrestricted**.

**Can't hear the Listen button:** check your phone's media volume. On very old Androids (< 6.0) the Web Speech API may not work inside WebView — the text itself still displays fine.

**Text is too small in the panel:** pinch-zoom inside the panel, or tap the eye icon to reduce the panel opacity and widen it by dragging from the drag bar.

---

## Package / file map

```
PDPrep/
├── build.gradle                                          (project-level)
├── app/
│   ├── build.gradle                                      (app-level)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/app.html                               (the cheat sheet content)
│       ├── java/com/carlos/pdprep/
│       │   ├── MainActivity.java                         (launcher screen)
│       │   └── OverlayService.java                       (floating bubble + panel)
│       └── res/
│           ├── drawable/ (bubble_bg, panel_bg, ic_launcher_fg)
│           ├── layout/ (activity_main, overlay_bubble, overlay_panel)
│           └── values/ (colors, strings, themes)
└── README.md                                             (this file)
```

Package name: `com.carlos.pdprep` · App name: **PD Prep**
