<div align="center">
  <img src="https://img.shields.io/badge/MOBILE-CODE-darkred?style=for-the-badge&logo=android" alt="Mobile Code Banner" />
  <h1>🩸 Mobile Code</h1>
  <p><strong>A Next-Generation AI-Powered CLI Agent Runner & Terminal for Android</strong></p>
  
  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-red.svg?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0)
  [![Platform](https://img.shields.io/badge/Platform-Android-darkred.svg?style=flat-square)]()
</div>

---

## 🌑 Overview

**Mobile Code** transforms your Android device into a powerful software development workstation, specifically engineered to run top-tier AI coding agents in your pocket. Built upon the robust foundation of Termux, it seamlessly sets up a full Linux environment and integrates directly with the most powerful CLI-based AI assistants in the world.

Whether you're fixing bugs on the go, architecting full applications, or generating scripts, Mobile Code acts as the ultimate bridge between your phone and agentic AI.

## 🔴 Elite Features

- 🤖 **Native CLI Agent Launcher**: Run world-class AI agents directly in the terminal via a sleek dropdown menu. Out-of-the-box support for:
  - **Claude Code** (Anthropic's autonomous coding CLI)
  - **Antigravity CLI** (DeepMind's advanced agent)
  - **Aider** (AI pair programming terminal)
  - **Codex** & **Gemini CLI**
- ⚡ **Auto-Bootstrapping**: No complex setup required. The app automatically downloads and installs Node.js, Python, npm, and the required CLI tools for your chosen agent the first time you select it.
- 🐧 **Full Linux Environment**: A complete Ubuntu-based PRoot Linux environment running on your phone, offering full access to `apt`, `npm`, `pip`, and standard Linux development tools.
- 🔄 **Smart Session Management**: Seamlessly switch between different AI agents. The app handles background processes safely, ensuring clean terminal sessions without lingering locks or corrupted caches.
- 🎨 **Aggressive Dark Mode Aesthetic**: Designed for OLED screens with a premium, focused dark theme and blood-red accents.

## 🚀 Getting Started

### Installation

1. Download the latest `app-debug.apk` or build it from source.
2. Install the APK on your Android device (ensure "Install from Unknown Sources" is enabled).
3. Launch the app. Allow the necessary storage permissions so the AI agents can read and write code to your phone's storage.

### Using AI Agents

1. At the top of the screen, tap the **Agent Dropdown Menu**.
2. Select your preferred AI (e.g., **Claude Code**).
3. If it's your first time, the terminal will automatically install the necessary dependencies (`nodejs`, `npm`, and the agent itself).
4. Follow the on-screen terminal prompts to authenticate the agent (e.g., via browser login).
5. Start coding!

## 🛠️ Build from Source

To build Mobile Code yourself, clone this repository and build it using Gradle:

```bash
# Clone the repository
git clone https://github.com/er-sunny-me/mobile-code.git

# Navigate into the directory
cd mobile-code

# Build the debug APK
./gradlew assembleDebug
```

> **Note:** The resulting APK will be available in `app/build/outputs/apk/debug/`.

## 🤝 Contributing

We love open-source and welcome contributions! Whether it's adding support for a new CLI agent, tweaking the UI, or fixing a bug, your help is appreciated.

**How to contribute:**
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/NewAgent`)
3. Commit your changes (`git commit -m 'Add support for new CLI Agent'`)
4. Push to the branch (`git push origin feature/NewAgent`)
5. Open a Pull Request on GitHub

## 📄 License

Mobile Code is open-source software licensed under the **GPLv3 License**. 
It is a derivative work based on the incredible [Termux](https://github.com/termux/termux-app) ecosystem.

---
<div align="center">
  <b>Developed by Er Sunny & Contributors</b><br>
  <i>Forging the future of mobile coding.</i>
</div>
