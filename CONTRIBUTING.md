# 🤝 Contributing to Novus IDE

First off, thank you for considering contributing to Novus IDE! It's people like you that make open-source software great.

## 📝 How to Contribute

### 1. Reporting Bugs
- Use the Bug Report template in the Issues tab.
- Provide clear steps to reproduce the issue.
- Include device information (Android version, device model).

### 2. Suggesting Features
- Use the Feature Request template.
- Explain *why* this feature would be useful and provide examples if possible.

### 3. Submitting Pull Requests
1. **Fork the repo** and create your branch from `main`.
   ```bash
   git checkout -b feature/your-awesome-feature
   ```
2. **Make your changes**. Ensure your code is clean and follows standard Android/Java conventions.
3. **Test your changes** by building a debug APK locally:
   ```bash
   ./gradlew assembleDebug
   ```
4. **Commit your changes**:
   ```bash
   git commit -m "feat: Add awesome new AI feature"
   ```
5. **Push to your fork** and submit a PR! Use the provided PR template.

## 🧑‍💻 Development Setup
You will need:
- Android Studio Ladybug or newer.
- JDK 17+
- An Android device (API 26+) for testing (Emulators do not work well with Termux's NDK).
