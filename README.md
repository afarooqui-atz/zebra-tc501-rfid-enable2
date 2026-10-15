# Zebra RFID TC501 / TC5x Android Project

This repository contains the complete source code to enable RFID on a Zebra TC501 (or TC51/TC52/TC53/TC57/TC58) paired with a Zebra RFID Sled (RFD40, RFD8500, or RFD90).

## Why You Don't Need Android Studio on Your PC
If you cannot install Android Studio locally, you have 4 fast cloud solutions:

### Solution 1: GitHub Actions (Recommended - 100% Free & Automated)
1. Push this project to a free repository on GitHub.
2. The included `.github/workflows/build-apk.yml` will automatically trigger on GitHub's cloud servers.
3. Once the build finishes (approx. 2-3 minutes), go to the **Actions** tab on your GitHub repository.
4. Click on the latest run, scroll down to **Artifacts**, and download `Zebra-RFID-TC501-Debug-APK.zip`.
5. Extract the `.apk` and transfer it to the TC501 (via Google Drive, USB MTP, or WebADB).

### Solution 2: GitHub Codespaces (Cloud Android Studio in your Browser)
1. In your GitHub repository, click **Code > Codespaces > Create codespace on main**.
2. A full VS Code IDE runs in your web browser.
3. Open the terminal and run:
   ```bash
   ./gradlew assembleDebug
   ```
4. Right-click `app/build/outputs/apk/debug/app-debug.apk` and click **Download**.

### Solution 3: Direct WebADB in the Browser (No PC Installs)
1. Connect your Zebra TC501 to your PC via a USB-C cable.
2. On the TC501, go to **Settings > About Phone** and tap **Build Number** 7 times.
3. Go back to **Settings > System > Developer Options** and enable **USB Debugging**.
4. Open Chrome or Edge and use the **WebUSB / WebADB Tool** in this app to push and install the APK directly to the TC501!

### Solution 4: Zebra StageNow Staging Barcode
1. Host your compiled APK on Google Drive, Dropbox, or GitHub Releases.
2. Open Zebra StageNow (or use the built-in StageNow configurator in this app).
3. The TC501 scans the 2D barcode, downloads the APK from your cloud URL over Wi-Fi, and installs it automatically.
