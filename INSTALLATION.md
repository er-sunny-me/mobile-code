# 📥 Installation Guide

Welcome to **Novus IDE**! Getting started is quick and easy. Follow the steps below to install and set up your mobile development environment.

## 📱 System Requirements

- **OS**: Android 8.0 (Oreo) or higher
- **RAM**: Minimum 3GB (4GB+ Recommended)
- **Storage**: At least 1.5GB of free space (for the Linux subsystem)
- **Architecture**: ARM64 or x86_64

## 🚀 Downloading & Installing

1. **Download the APK**
   Head over to our [Releases](https://github.com/er-sunny-me/Novus-IDE/releases) page and download the latest `Novus-IDE-v[LatestVersion]-release.apk`.

2. **Enable Unknown Sources**
   If this is your first time installing an APK outside the Play Store, go to **Settings > Apps & Security** and enable **"Install Unknown Apps"** for your web browser or file manager.

3. **Install the APK**
   Tap on the downloaded APK file and click **Install**.

## ⚙️ Initial Setup

1. **Launch Novus IDE**
   Open the app from your app drawer. 
2. **Bootstrap Installation**
   On the first launch, the app will download and install the underlying Linux (Termux) environment. Please ensure you are connected to the internet and **do not close the app** during this process.
3. **Storage Permissions**
   Run the following command in the terminal to allow Novus IDE to access your phone's storage (useful for editing external files):
   ```bash
   termux-setup-storage
   ```

## 🛠️ Installing Packages

Novus IDE uses `apt` and `pkg` just like Ubuntu/Debian. To install a package:

```bash
pkg update && pkg upgrade
pkg install nodejs python git
```

You are now ready to start coding! Check out [SETUP_AI.md](SETUP_AI.md) to supercharge your IDE with Autonomous AI.
