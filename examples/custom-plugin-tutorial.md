# Custom Plugin Tutorial (MCP)

Novus IDE natively supports the **Model Context Protocol (MCP)**. This means you can extend the AI's capabilities with local tools!

## Setup a Local SQLite Plugin
1. Open Novus IDE terminal.
2. Create an MCP server using Node.js that exposes a `query_db` tool.
3. In Novus IDE, go to **MCP Configurations**.
4. Add your MCP server endpoint (e.g., `http://localhost:3000/mcp`).
5. Novus IDE will automatically fetch the `query_db` tool schema and attach it to the AI's system prompt!

Now, you can say:
> "Run a query to find all users in my local database."

And the AI will seamlessly call the `query_db` tool on your local server.
