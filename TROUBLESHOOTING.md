# 🩹 Troubleshooting Guide

Having issues with Novus IDE? Check out the common problems and their solutions below.

### 1. "Termux environment failed to install"
**Cause:** Poor internet connection or lack of storage space during the first launch.
**Fix:** 
- Ensure you have at least 1.5GB of free space.
- Go to Android Settings > Apps > Novus IDE > Storage and tap **"Clear Data"**.
- Relaunch the app connected to a stable Wi-Fi network.

### 2. "Permission Denied" when editing files
**Cause:** Android storage permissions aren't fully granted.
**Fix:** Run `termux-setup-storage` in the terminal and accept the permission prompt.

### 3. AI says "Invalid API Key" or "Rate Limited"
**Fix:** 
- Go to Settings and verify your API key is correct.
- If using a free tier API (like Groq or Gemini Free), you may have hit a rate limit. Wait a few minutes or upgrade your API tier.

### 4. Background Agent Stops Randomly
**Cause:** Android's aggressive battery optimization killed the background process.
**Fix:** Go to Android Settings > Apps > Novus IDE > Battery and set it to **"Unrestricted"**.

### 5. Web Search feature isn't working
**Fix:** Check that you have selected a Search Provider (e.g., Tavily or Firecrawl) in Settings and have provided a valid API key for it.

---
Still having issues? Feel free to open a [GitHub Issue](https://github.com/er-sunny-me/Novus-IDE/issues).
