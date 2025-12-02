# MCP Tool Playground

This project provides a collection of **MCP (Model Control Protocol) tools** implemented in Java, along with an interactive web-based simulation environment.
It is designed for **experimental purposes** and **educational use** — not for production deployment.

## Features

- **Tool Implementations**:
  - Create new files (`CreateNewFileTool`)
  - Find files by glob pattern (`FindFilesByGlobTool`)
  - Manage fields in Java classes (`ManageJavaFieldsTool`)
  - Manage methods in Java classes (`ManageJavaMethodsTool`)
  - Read text files (`ReadTextFileTool`)
  - Play and search videos (`VideoPlayerTool` and `VideoSearchTool`)

- **Playground Server**:
  A combined **HTTP/WebSocket server** that allows users to simulate MCP tool interactions via a browser.
  The server accepts custom tool definitions and relays requests/responses between an LLM (e.g., OpenAI) and human operators.

## Purpose

This playground is intended for:
- **Testing and experimenting** with MCP tool implementations.
- **Training purposes**, such as learning how MCP tools work in an interactive environment.
- **Debugging** structured tool interactions with large language models.

## Usage

1. Run the server with `java McpPlaygroundServerMain <host-ip> <port> llm-url public-path`.
The llm-url is the chat/completions URL of the LLM server (e.g. llama.cpp).
The public-path is the public-path of a local installation of https://github.com/ggerganov/llama.cpp/.
2. Use the web interface to define a custom tool or select a preset.
3. Interactively respond to LLM tool calls and observe the behavior.

### Example Presets
The interface includes predefined tool examples (e.g., weather, currency conversion, news fetching) for quick testing.

### Security Note
The tools enforce **strict security checks** (e.g., project directory filtering, path traversal prevention) to ensure safe usage.

## Limitations
- Not suitable for production environments.
- Requires manual configuration and response handling.
- Focused on **MCP tool development and understanding**, not on real-world LLM integration.
