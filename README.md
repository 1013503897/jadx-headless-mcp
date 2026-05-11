# jadx-headless-mcp

Headless MCP server for Android APK static analysis, built directly on `jadx-core`. No JADX GUI required, no Python adapter, no plugin to install — just one JVM process speaking MCP over stdio.

## Why

The existing JADX MCP servers (`zinja-coder/jadx-mcp-server`, `mobilehackinglab/jadx-mcp-plugin`) all require `jadx-gui` to be running with a plugin loaded. That's awkward for:

- batch APK analysis pipelines
- CI / Docker / server deployments
- running 5+ instances in parallel (GUI baseline is 300–500 MB each)
- low-latency tool calls where the Python ↔ HTTP ↔ JVM hop adds up

This project loads APKs through the `jadx-core` library directly and exposes 17 read-only analysis tools as a single MCP stdio server.

## Tools

| Tool | Description |
|---|---|
| `get_app_info` | package name, version, minSdk/targetSdk, permissions |
| `get_android_manifest` | full decoded AndroidManifest.xml |
| `get_main_activity_class` | LAUNCHER activity FQN |
| `list_classes` | paginated class FQN list |
| `search_classes_by_keyword` | substring search over class FQNs |
| `get_class_source` | decompiled Java source |
| `get_smali_of_class` | smali disassembly |
| `get_methods_of_class` | method signatures |
| `get_method_by_name` | source of a single method |
| `get_fields_of_class` | field list with types |
| `search_method_by_name` | global method search |
| `get_xrefs_to_class` | references to a class |
| `get_xrefs_to_method` | references to a method |
| `get_xrefs_to_field` | references to a field |
| `get_strings` | string resources with optional filter |
| `list_resource_files` | resource file names |
| `get_resource_file` | resource file content (text or base64) |

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

```bash
./build/install/jadx-headless-mcp/bin/jadx-headless-mcp \
  --apk /path/to/app.apk \
  --max-source-bytes 200000
```

The server speaks MCP over stdio. Logs go to stderr.

## Claude Code MCP configuration

`~/.claude.json` (or your project-scoped MCP config):

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat",
      "args": [
        "--apk", "C:/work/current.apk",
        "--max-source-bytes", "200000"
      ]
    }
  }
}
```

To analyze multiple APKs in parallel, register multiple entries with different `--apk` values (e.g. `jadx-headless-foo`, `jadx-headless-bar`).

## Architecture

```
Claude Code  ──stdio JSON-RPC──>  jadx-headless-mcp (JVM)
                                          │
                                          ├── MCP Kotlin SDK (stdio server)
                                          │
                                          └── jadx-core API
                                                 ├── JadxDecompiler.load(apk)
                                                 ├── getClasses / getResources
                                                 └── JavaClass.getCode / getSmali / getUseIn
```

Lazy decompilation: only `get_class_source` and `get_smali_of_class` trigger per-class decompilation. Index building at startup is metadata-only.

## Memory tuning

The launcher sets `-XX:MaxRAMPercentage=60.0` by default. Override via `JAVA_OPTS`:

```bash
JAVA_OPTS="-Xmx8g" ./bin/jadx-headless-mcp --apk app.apk
```

## Limitations

- One APK per process (matches the JADX GUI session model; spawn multiple servers for multiple APKs)
- Read-only; no rename / mutation tools in v1
- Java decompilation only; for full Unity / IL2CPP analysis, pair with an IL2CPP dump
- AndroidManifest is parsed with regex (sufficient for activity/permission lookup; not a full XML parser)

## License

Apache-2.0.
