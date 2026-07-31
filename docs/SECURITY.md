# Security and Privacy

## API Keys
Your API keys (OpenAI, Gemini, Groq, Firecrawl, Tavily, etc.) are stored locally on your device using Android's `SharedPreferences` in private mode. They are **never** transmitted to any third-party server other than the specific API provider you are querying.

## App Permissions
- **Storage Permission**: Used strictly to allow you to edit your own files in the code editor, and to allow the AI to read the files you explicitly tag.
- **Internet Permission**: Required for downloading linux packages via `apt`, making requests to the LLM APIs, and fetching web search results.

## Vulnerability Reporting
If you discover a security vulnerability within Novus IDE, please DO NOT open a public issue. Instead, send an email or a private message to the maintainers.
