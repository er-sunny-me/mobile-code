# 🧠 Setting Up Autonomous AI

Novus IDE isn't just an editor; it's an intelligent workspace powered by your choice of Language Models (LLMs) and real-time Search APIs.

## 🔑 1. Configuring Your AI Provider

By default, Novus IDE requires you to provide an API key to communicate with the AI model. 

1. Open **Novus IDE** and tap the **Settings (Gear Icon)** in the side drawer.
2. Scroll to the **AI Configuration** section.
3. **Select Provider**: Choose from providers like `OpenAI`, `Anthropic`, `Gemini`, `Groq`, `NVIDIA NIM`, or `AgentRouter`.
4. **Enter API Key**: Paste your secure API key.
5. **Select Model**: Choose the specific model (e.g., `gpt-4o`, `claude-3-5-sonnet`, `gemini-1.5-pro`).

## 🌐 2. Enabling Real-Time Web Search

Novus IDE's AI can autonomously search the internet when it lacks up-to-date knowledge or needs to debug an obscure error.

1. Go to **Settings > Search Provider**.
2. Select your preferred engine:
   - **Tavily** (Highly recommended for AI)
   - **Brave Search**
   - **Firecrawl**
   - **Serper**
3. Enter the respective **Search API Key**.

## 🛠️ 3. Using the AI

### 💬 Chat Mode
Tap the **Ask AI** button at the bottom of the screen. You can ask general programming questions or request new projects.

**Example Prompt:** 
> "Create a new React project in a folder called `my-app`. Include a `.env.example` and a `README.md`."

The AI will generate an action plan, ask for your confirmation, and then **autonomously execute all the necessary terminal commands** to build it.

### 🎯 @ File Tagging
While chatting with the AI, type `@` to bring up your workspace files. Tap a file to instantly inject its entire content into the AI's context!

### 🚨 Smart Error Solving
Did your Node.js script crash? Did Python throw a traceback? 
1. Long-press the error text in the terminal.
2. Tap **"Fix with AI"**.
3. The AI will extract the error, find the offending lines of code in your files, and give you an instant, token-optimized fix.
