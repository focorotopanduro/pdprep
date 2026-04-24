# Install PD Prep on your Android — easy path

This path needs **no software on your PC**. The build happens in the cloud. You just upload the folder once and download an APK.

---

## Step 1 — One-time GitHub setup (5 minutes)

1. Go to **https://github.com/signup** — create a free account if you don't have one. Any username, any email.
2. Click the **`+`** icon top right → **New repository**.
3. Name it `pdprep` (or anything). Leave everything else default. Click **Create repository**.

## Step 2 — Upload the project folder (3 minutes)

On the empty repo page, click **"uploading an existing file"** (it's a small link in the middle of the page).

Then from Windows Explorer:

1. Open `C:\Users\Owner\Desktop\PDPrep\`
2. Select **ALL** files and folders inside it (Ctrl + A)
3. Drag them into the GitHub upload area
4. Wait for upload to finish (small folder, ~30 seconds)
5. Scroll down, click **Commit changes**

> **Important**: drag the *contents* of `PDPrep`, not the `PDPrep` folder itself. GitHub needs `build.gradle`, `app/`, `.github/` at the repo root.

## Step 3 — Watch the build run (3 minutes)

1. At the top of your repo, click the **Actions** tab.
2. You'll see a workflow called **"Build APK"** with a yellow dot (running).
3. Wait ~3 minutes until it turns green ✓.

## Step 4 — Download the APK to your phone

Easiest way — on your phone:

1. Open your phone's browser, go to `github.com/YOUR-USERNAME/pdprep/releases/latest`
2. Under **Assets**, tap **`PDPrep.apk`** — it downloads straight to your phone.
3. Open your phone's Files app → Downloads → tap `PDPrep.apk`
4. Android asks "Install unknown app?" — tap **Settings** → toggle **Allow from this source** → back button
5. Tap **Install** → **Open**

## Step 5 — Grant the overlay permission (one time)

1. App opens on "Step 1: Grant 'Display over other apps' permission"
2. Tap **Grant Permission**
3. Toggle **PD Prep** → **ON** → press back
4. Tap **Start Floating Bubble**
5. Done. Open Zoom / Teams / any app — the gold **PD** bubble stays on top.

---

## Using the bubble during a call

- **Short-tap bubble** → opens the cheat-sheet panel
- **Drag bubble** → moves anywhere (snaps to edge when released)
- **Long-press bubble** (~½ sec, you'll feel a buzz) → closes the overlay completely
- Inside the panel:
  - **Drag bar** (top of panel) → move the panel
  - **👁 icon** → cycle transparency 96% → 75% → 55% so you can see Zoom underneath
  - **✕ icon** → collapse back to bubble
  - **🔊 Listen** buttons read the answer aloud — tap the same button again to stop

---

## Updating the app later

Edit any file on your GitHub repo (e.g. tweak the HTML cheat sheet). Every push auto-rebuilds the APK into the same "latest" release. Re-download on your phone, reinstall over the top.

---

## Troubleshooting

**"The workflow failed" (red X):** click the red run → click the failed step → read the error. Usually a typo in a file or a missing file. Re-upload the missing bit, commit, it re-runs.

**"App not installed" on phone:** uninstall any old version of PD Prep first, then try again.

**"Can't find Install button":** your phone's "unknown sources" is locked. Settings → Apps → Special access → Install unknown apps → pick your browser → Allow.

**Bubble doesn't appear after Start:** the overlay permission isn't on. Settings → Apps → PD Prep → **Display over other apps** → ON.

**Bubble dies when the screen locks:** battery optimization killed the service. Settings → Apps → PD Prep → Battery → **Unrestricted**.
