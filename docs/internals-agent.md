# InternalsAgent — query agents-kt internals from your IDE

The InternalsAgent is a self-hosting docs agent: each of its skills corresponds to a source file in the framework. Exposed over MCP, your IDE's AI assistant (Cursor, Claude Desktop, etc.) can call those skills as tools to get curated, authoritative knowledge about how agents-kt works — straight from the source.

## Quickstart

From the project root:

```bash
./gradlew runInternalsAgent
```

The server binds to `http://localhost:8765/mcp` and prints a list of every skill it exposes.

To pick a different port:

```bash
./gradlew runInternalsAgent --args="9000"
```

The server runs until you `Ctrl+C` it.

## IDE wiring

### Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (Mac) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows). Add:

```json
{
  "mcpServers": {
    "agents-kt-internals": {
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

Restart Claude Desktop. The `agents-kt-internals` tools will appear in the tool list. Ask things like:

- *"What does Pipeline.kt do?"*  → routes to `composition_pipeline_pipeline_kt`
- *"How does the agentic loop handle budget caps?"*  → routes to `model_agenticloop_kt` and `model_budgetconfig_kt`
- *"Walk me through how a Branch streams events."*  → routes to `composition_branch_branchsessionextension_kt`

### Cursor

Edit `~/.cursor/mcp.json` with the same shape:

```json
{
  "mcpServers": {
    "agents-kt-internals": {
      "url": "http://localhost:8765/mcp"
    }
  }
}
```

Restart Cursor (or reload the MCP config from the command palette).

### Other MCP clients

Anything that speaks the MCP Streamable HTTP transport can connect — point it at `http://localhost:8765/mcp`. See [the MCP spec](https://modelcontextprotocol.io) for client conformance.

## Skill naming convention

Every skill is named `<package-path>_<filename>_kt`, derived mechanically from the source file's path. The path is the authoritative key.

| Source file | Skill name |
|---|---|
| `src/main/kotlin/agents_engine/core/Agent.kt` | `core_agent_kt` |
| `src/main/kotlin/agents_engine/composition/branch/BranchBuilder.kt` | `composition_branch_branchbuilder_kt` |
| `src/main/kotlin/agents_engine/mcp/McpClient.kt` | `mcp_mcpclient_kt` |
| `agents-kt-ksp/src/main/kotlin/agents_engine/ksp/SchemaEmitter.kt` | `ksp_schemaemitter_kt` |

If your IDE LLM is having trouble picking the right skill, you can name it directly: *"call `composition_pipeline_pipeline_kt` and tell me what `then` returns"*.

## What each skill returns

Each skill returns a curated markdown adjunct for the corresponding source file. The adjunct covers:

- The file's purpose (what it owns, what it does NOT own).
- Public API shape and DSL surface.
- Subtle behaviors and contracts (freeze semantics, fail-fast points, race-safe patterns).
- Pointers to related files for cross-reference.

The adjuncts live alongside the source in `src/main/resources/internals-agent/<path>.md`. They're kept synchronized with each file's top-of-file `/** ... */` KDoc — deliberate redundancy so a human reading source sees the same story the IDE LLM sees.

## Adding a new file to the agent

Drop a new adjunct under `src/main/resources/internals-agent/<package>/<File>.md` with this shape:

```markdown
---
description: <one-line tool description shown to the IDE LLM — be specific so the LLM picks it correctly>
---

# `<package>/<File>.kt` — <short purpose>

<body — markdown, anything that helps the LLM understand the file>
```

The `description:` line in the frontmatter is what the IDE's LLM sees when picking a tool. Make it precise: name the file, name the public types, name the key behaviors. The body is what the LLM gets back as the tool result — write for someone who needs to reason about the file but can't easily open it.

That's it — no `InternalsAgent.kt` edit needed. The agent scans `src/main/resources/internals-agent/` at construction time, derives the skill name from the path, and registers the skill automatically.

## Architecture

`buildInternalsAgent()` returns an `Agent<String, String>` with no `model { }` configured — the IDE's LLM does all the reasoning. Each skill is `implementedBy { _ -> loadResource(path) }`: a pure data fetch.

`McpServer.from(agent)` exposes every skill as an MCP tool over Streamable HTTP. Tool name = skill name; tool description = adjunct's `description:` frontmatter; tool result = the rest of the adjunct's body.

See:
- `src/main/kotlin/agents_engine/runtime/internals/InternalsAgent.kt` — the scanner + registration.
- `src/main/kotlin/agents_engine/runtime/internals/Main.kt` — the MCP server runner.
- `src/main/kotlin/agents_engine/mcp/McpServer.kt` — the HTTP/MCP transport.
- `src/main/kotlin/agents_engine/core/Resources.kt` — `loadResource` classpath helper.

## Troubleshooting

**Port already in use.** Pass a different port:
```bash
./gradlew runInternalsAgent --args="9999"
```

**Claude Desktop doesn't show the tools.** Confirm the JSON config is valid (`jq . < ~/Library/Application\ Support/Claude/claude_desktop_config.json`). Restart Claude Desktop fully (quit + relaunch, not just close the window). The server logs every incoming MCP request, so check `runInternalsAgent`'s stdout while sending a query.

**Skill returns "Resource not found".** Should never happen — the agent fails fast at construction if any adjunct is missing. If it does, file an issue with the skill name and the server's stderr.

**Adjunct count drift.** A new test (`InternalsAgentTest.kt`) verifies the skill count matches the adjunct file count. If you add a new adjunct, bump the expected count in the test.
