# Architecture Overview

Novus IDE combines the open-source Terminal Emulator and Linux environment from Termux with a natively integrated AI assistant and Code Editor.

## Core Components
- **Termux Base**: Handles terminal emulation (`terminal-view`), PTY bridging, and the `apt`/`pkg` package managers.
- **Novus IDE Code Editor**: A custom Android View structure using advanced text rendering, file explorers, and a bottom quick-action bar.
- **AI Core (`AIChatManager`)**: A highly optimized orchestration layer that interfaces directly with various LLM APIs (OpenAI, Gemini, Groq, Anthropic, Firecrawl). It includes:
  - Token-optimized error trimming.
  - Native XML-tag based tool calling for `<search_web>` and `<run_command>`.
  - `@` file tagging mechanism to recursively bundle file contents into the prompt.
- **Agent Orchestrator**: A specialized sub-agent system designed for multi-step autonomous tasks.
