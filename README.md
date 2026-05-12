# jadx-headless-mcp

**English** | [简体中文](README.zh-CN.md)

Headless MCP server for Android APK static analysis, built directly on `jadx-core`. No JADX GUI required, no Python adapter, no plugin to install — just one JVM process speaking MCP over stdio.

## Why

The existing JADX MCP servers ([`zinja-coder/jadx-mcp-server`](https://github.com/zinja-coder/jadx-mcp-server), [`mobilehackinglab/jadx-mcp-plugin`](https://github.com/mobilehackinglab/jadx-mcp-plugin)) all require `jadx-gui` to be running with a plugin loaded. That's awkward for:

- batch APK analysis pipelines
- CI / Docker / server deployments without a display
- running multiple instances in parallel (GUI baseline is 300–500 MB each)
- low-latency tool calls where the Python ↔ HTTP ↔ JVM hop adds up

This project loads APKs through the `jadx-core` library directly and exposes 20 tools as a single MCP stdio server.

## Tools

### Session management
| Tool | Description |
|---|---|
| `status` | Whether an APK is loaded; path, class/resource counts, load duration |
| `load_apk` | Load an APK / DEX / JAR (replaces any current one) |
| `unload_apk` | Release the loaded APK and free memory |

### Static analysis (require a loaded APK)
| Tool | Description |
|---|---|
| `get_app_info` | Package name, version, minSdk/targetSdk, permissions |
| `get_android_manifest` | Full decoded AndroidManifest.xml |
| `get_main_activity_class` | LAUNCHER activity FQN |
| `list_classes` | Paginated class FQN list |
| `search_classes_by_keyword` | Substring search over class FQNs |
| `get_class_source` | Decompiled Java source |
| `get_smali_of_class` | Smali disassembly |
| `get_methods_of_class` | Method signatures |
| `get_method_by_name` | Source of a single method |
| `get_fields_of_class` | Field list with types |
| `search_method_by_name` | Global method search |
| `get_xrefs_to_class` | References to a class |
| `get_xrefs_to_method` | References to a method |
| `get_xrefs_to_field` | References to a field |
| `get_strings` | String resources with optional filter |
| `list_resource_files` | Resource file names |
| `get_resource_file` | Resource file content (text or base64) |

## Build

Requires JDK 17+.

```bash
./gradlew installDist
```

Output: `build/install/jadx-headless-mcp/bin/jadx-headless-mcp` (and `.bat` on Windows).

For a single fat jar:

```bash
./gradlew shadowJar
# build/libs/jadx-headless-mcp-<version>-all.jar
```

## Run

Empty start (load an APK later via `load_apk` tool):

```bash
./build/install/jadx-headless-mcp/bin/jadx-headless-mcp
```

Or eager-load on startup:

```bash
./build/install/jadx-headless-mcp/bin/jadx-headless-mcp \
  --apk /path/to/app.apk \
  --max-source-bytes 200000
```

The server speaks MCP over stdio. Logs go to stderr.

## Claude Code MCP configuration

Register one entry, swap APKs at runtime — no config edit needed:

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

Then ask the AI to call `load_apk` with the path you want to analyze. Use `unload_apk` to free memory or switch to a different one.

If you only ever analyze a single APK and want it ready immediately:

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat",
      "args": ["--apk", "C:/work/current.apk"]
    }
  }
}
```

## Architecture

```
Claude Code  ──stdio JSON-RPC──>  jadx-headless-mcp (JVM)
                                          │
                                          ├── MCP Kotlin SDK (stdio server)
                                          │
                                          └── SessionHolder
                                                 │  (Mutex-serialized load/unload)
                                                 └── JadxSession
                                                        └── JadxDecompiler (jadx-core)
```

Lazy decompilation: only `get_class_source` and `get_smali_of_class` trigger per-class decompilation. Index building at startup is metadata-only.

## Memory tuning

The launcher sets `-XX:MaxRAMPercentage=60.0` by default. Override via `JAVA_OPTS`:

```bash
JAVA_OPTS="-Xmx8g" ./bin/jadx-headless-mcp
```

## Limitations

- One APK at a time per process (call `load_apk` to swap)
- Read-only; no rename / mutation tools yet
- Java decompilation only; for Unity / IL2CPP, pair with an IL2CPP dump
- AndroidManifest is parsed with regex (sufficient for activity/permission lookup; not a full XML parser)

## Acknowledgements

- [`skylot/jadx`](https://github.com/skylot/jadx) — the underlying decompiler
- [`modelcontextprotocol/kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk) — MCP server framework

## License

Apache-2.0.
