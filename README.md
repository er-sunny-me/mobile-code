<div align="center">
  <img src="https://img.shields.io/badge/NOVUS-IDE-darkred?style=for-the-badge&logo=android" alt="Novus IDE Banner" />
  <h1>🩸 Novus IDE</h1>
  <p><strong>A Next-Generation, Autonomous AI-Powered Coding Workspace & Linux Terminal for Android</strong></p>
  
  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-red.svg?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0)
  [![Platform](https://img.shields.io/badge/Platform-Android-darkred.svg?style=flat-square)]()
  [![AI Powered](https://img.shields.io/badge/AI-Autonomous-red.svg?style=flat-square)]()
  [![Search Integrations](https://img.shields.io/badge/Realtime-Web_Search-darkred.svg?style=flat-square)]()
</div>

---

## 🌑 Overview

**Novus IDE** transforms your Android device into a complete, portable software development workstation with a striking **Dark & Red** aesthetic. Built upon the robust foundation of Termux, it integrates a powerful **Autonomous AI Agent** directly into your terminal and code editor. 

Whether you are fixing obscure bugs on the go, writing complete applications, or managing remote servers, Novus IDE provides everything you need. It doesn't just autocomplete code—it **thinks, searches the web in real-time, and executes commands autonomously**.

## 🔴 Elite Features

- 🧠 **Autonomous AI Agent**: Beyond a simple chat. The AI can autonomously run terminal commands, create/delete files, and navigate your workspace to build full projects from a single prompt.
- 🌐 **Real-Time Web Search**: Native integrations with **Firecrawl**, **Tavily**, **Brave**, and **Serper**. If the AI doesn't know something or encounters a weird error, it will autonomously search the web for the latest documentation and solutions!
- 🎯 **@ File Tagging**: Type `@` in the AI chat to instantly summon a list of your project files. Select a file to instantly inject its entire codebase into the AI's context for pinpoint precision.
- 🚨 **Smart Error Solving**: Select terminal errors and tap "Fix with AI". The AI intelligently trims the log, pulls the exact surrounding code context, and delivers a concise code fix without wasting tokens.
- 💻 **Advanced Code Editor**: A full-featured native editor built for mobile. Enjoy syntax highlighting, file tagging, and an intelligent bottom bar.
- 🐧 **Full Linux Environment**: A complete Linux terminal experience powered by the Termux ecosystem. Access `apt` and `pkg` for Node.js, Python, Rust, C++, and more.
- 🎨 **Dark & Blood Red Aesthetic**: Designed for the night owls. A premium, aggressive dark theme with red accents that looks incredible on modern OLED mobile displays.

## 📚 Documentation

Dive deeper into Novus IDE with our comprehensive guides:
- [📥 Installation Guide](INSTALLATION.md)
- [🧠 AI & Search Setup](SETUP_AI.md)
- [🩹 Troubleshooting](TROUBLESHOOTING.md)
- [🗺️ Roadmap](ROADMAP.md)
- [🤝 Contributing Guidelines](CONTRIBUTING.md)

## 🚀 Getting Started

### Installation

1. Go to the [Releases](https://github.com/er-sunny-me/Novus-IDE/releases) page.
2. Download the latest `Novus-IDE.apk`.
3. Install the APK on your Android device (ensure "Install from Unknown Sources" is enabled).
4. Launch the app and let the Linux environment bootstrap automatically.

### Configuring AI & Real-Time Search

- Open the **Settings** menu.
- **Select your AI Provider**: Choose from OpenAI, Anthropic, Gemini, Groq, NVIDIA NIM, AgentRouter, and many more. Enter your API Key.
- **Enable Real-Time Search**: Select `FIRECRAWL`, `TAVILY`, or `BRAVE` and enter your Search API key. The AI will now use this to browse the web autonomously!
- **Permission Mode**: Choose between *Full Automation*, *Code Only*, or *Ask Everything* to control how autonomously the AI operates.

## 🛠️ Build from Source

To build Novus IDE yourself, clone this repository and build it using Gradle:

```bash
# Clone the repository
git clone https://github.com/er-sunny-me/Novus-IDE.git

# Navigate into the directory
cd Novus-IDE

# Build the debug APK
./gradlew assembleDebug
```

> **Note:** The resulting APK will be available in `app/build/outputs/apk/debug/`.

## 🤝 Contributing

We love open-source and welcome contributions! Whether it's adding a new AI provider, tweaking the dark theme, or fixing a bug, your help is appreciated.

**How to contribute:**
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AggressiveTheme`)
3. Commit your changes (`git commit -m 'Add some AggressiveTheme'`)
4. Push to the branch (`git push origin feature/AggressiveTheme`)
5. Open a Pull Request on GitHub

## 📄 License

Novus IDE is open-source software licensed under the **GPLv3 License**. 
It is a derivative work of [Termux](https://github.com/termux/termux-app).

---
<div align="center">
  <b>Developed with 🩸 by Er Sunny & Contributors</b><br>
  <i>Forging the future of mobile coding.</i>
</div>
