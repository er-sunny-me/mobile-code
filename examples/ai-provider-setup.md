# Setup Local AI Providers

You can run models locally on your phone using Termux and connect Novus IDE to them!

1. Open Novus IDE terminal.
2. Install a local inference engine like `llama.cpp` or `Ollama` via `pkg` (if supported) or by compiling from source.
3. Start the local server (e.g., `http://localhost:11434/v1`).
4. Go to Novus IDE Settings -> AI Configuration.
5. Select **Custom Provider**.
6. Set the Custom Base URL to `http://localhost:11434/v1/chat/completions`.
7. Set the API Key to anything (e.g., `sk-dummy`).
8. Enjoy completely offline, private AI assistance!
