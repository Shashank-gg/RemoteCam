# RemoteCam
Use one phone as the camera and another as a live viewfinder + remote control (30fps 16:9 live view, photo + video modes, zoom presets, exposure, torch, flip, tap-to-focus, timer). Photos go to Pictures/RemoteCam, videos to Movies/RemoteCam on the camera phone.

## Get the APK
1. Create a GitHub repo, upload all files of this folder (keep the `.github` folder).
2. Open the **Actions** tab → "Build APK" runs automatically (or click *Run workflow*).
3. When finished, open the run → **Artifacts** → download `RemoteCam-apk` → unzip → `app-debug.apk`.
4. Install on both phones (allow "install unknown apps").

## Use
1. Put both phones on the same Wi-Fi (or turn on a hotspot on one and join from the other).
2. Camera phone: open app → "CAMERA". It shows its IP.
3. Other phone: open app → "REMOTE" → type the IP → Connect.
