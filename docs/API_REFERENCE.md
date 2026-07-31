# AI Integration API Reference

Novus IDE interfaces with multiple LLMs.

### Endpoints
We dynamically select the base URL depending on the provider chosen in settings:
- **OpenAI**: `https://api.openai.com/v1/chat/completions`
- **Anthropic**: `https://api.anthropic.com/v1/messages` (Internal proxy adapter used)
- **Groq**: `https://api.groq.com/openai/v1/chat/completions`
- **Gemini**: `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`
- **AgentRouter**: `https://api.agentrouter.com/v1/chat/completions`
- **NVIDIA NIM**: `https://integrate.api.nvidia.com/v1/chat/completions`

### Tool Calling Format
Because not all open-source models support native JSON tool-calling, Novus IDE intercepts XML-like tags in the LLM's raw text stream.
**Available Tags:**
- `<run_command>your_bash_command</run_command>`
- `<search_web query="your search query" />`
- `<write_file path="file.py">contents</write_file>`
