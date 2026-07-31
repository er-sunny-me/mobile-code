# Web Search Integration Example

Novus IDE has a built-in `WebSearchManager` that hooks into providers like Firecrawl and Tavily.

## How it works
When you ask the AI a question like:
> "What is the latest score in the IPL match today?"

The AI will output the following hidden tag:
```xml
<search_web query="latest IPL match score today" />
```

Novus IDE intercepts this tag *before* showing it to the user. It runs the HTTP request in the background using your configured API key, and injects the result back into the prompt:

```text
Search results for `latest IPL match score today`:
[The fetched results...]
```

The AI then reads this and provides you with the final answer seamlessly!
