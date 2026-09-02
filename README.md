# jadx-headless-mcp

**English** | [简体中文](README.zh-CN.md)

Headless MCP server for Android APK static analysis, built directly on `jadx-core`. No JADX GUI required, no Python adapter, no plugin to install — just one JVM process speaking MCP over stdio.

## Why

jadx is two parts: the `jadx-core` decompiler engine, and a GUI wrapped around it for a human to click through by hand.

Give a capable LLM direct access to `jadx-core` and the GUI loses its reason to exist. The model walks the same class tree, resolves the same xrefs, reads the same source — headless and scriptable. As models get stronger, Java-layer reverse engineering increasingly runs end to end from the model, without a reverse engineer driving it by hand.

So this server drops the GUI and exposes `jadx-core` as ~26 MCP tools over stdio.

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
| `get_class_source` | Decompiled Java source. Auto-falls back to smali when jadx hits anti-decompile stubs (marker `// [jadx java-decompile failed → smali]`; `smali_fallback=false` to opt out). Resolves inner/`$Companion` names. |
| `get_class_summary` | Class skeleton (method signatures, fields, inner-class names) — no method bodies |
| `get_smali_of_class` | Smali disassembly. Pageable via `offset` (byte offset into full smali); header reports `total_bytes`/`next_offset` |
| `get_methods_of_class` | Method signatures |
| `get_method_by_name` | Java source of a single method; same smali fallback as `get_class_source` |
| `get_method_body` | One method as Java-if-possible-else-smali, in a JSON envelope (`mode`, `fell_back`, `markers`); handles overloads |
| `get_method_smali` | Smali of a single method (all overloads), sliced from the class disassembly |
| `get_inner_classes` | Inner/nested classes with both dotted `full_name` and raw `$`-joined `raw_name` |
| `resolve_class` | Resolve an inner/`$Companion`/fuzzy class name to its exact FQN, or list candidates |
| `get_fields_of_class` | Field list with types |
| `search_method_by_name` | Global method search (includes inner-class methods) |
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

For a single portable fat jar:

```bash
./gradlew shadowJar
# build/libs/jadx-headless-mcp-<version>-all.jar
```

> **No build needed?** Prebuilt artifacts (fat jar + distribution zip) are attached to every [GitHub Release](../../releases) — download one and point your MCP config at it directly.

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

## MCP client configuration

`jadx-headless` is a standard stdio MCP server, so any MCP-capable client can drive it — Claude Code, Claude Desktop, Cursor, Windsurf, Cline, Roo Code, VS Code / Copilot, LM Studio, Zed, Codex, Gemini CLI, and others. Claude Code has the smoothest path; the rest take one small config entry — see [Other MCP clients](#other-mcp-clients).

### Claude Code

#### Quickest: register with the CLI

One command, no JSON editing. `-s user` writes to the global `~/.claude.json`; the default `local` scope only applies to the current project:

```bash
# installDist launcher
claude mcp add jadx-headless -s user -- C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat

# ...or the portable fat jar (single file; needs java on PATH)
claude mcp add jadx-headless -s user -- java -jar C:/tools/jadx-headless-mcp-<version>-all.jar
```

Then ask the AI to call `load_apk` with the path you want to analyze. Use `unload_apk` to free memory or switch to a different one — no config edit needed.

#### Or edit the config by hand

Register one entry, swap APKs at runtime:

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

To point at the portable fat jar instead:

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "java",
      "args": ["-jar", "C:/tools/jadx-headless-mcp-<version>-all.jar"]
    }
  }
}
```

If you only ever analyze a single APK and want it ready immediately, append the APK path via `args`:

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

> **Note:** `java -jar` skips the launcher's default JVM args (e.g. `-XX:MaxRAMPercentage=60`). stdout stays clean either way — slf4j-simple logs to stderr and `Main.kt` redirects `System.out` to stderr — but if you need a memory ceiling, pass it yourself via `JAVA_OPTS` / `-XX`. The installDist launcher keeps those defaults.

### Other MCP clients

Point the client at the same launcher (or `java -jar` the fat jar); only the surrounding config format differs. In every case `command` is the launcher (or `java`), and for the fat jar `args` is `["-jar", "<path-to>-all.jar"]`. `--apk` / `--max-source-bytes` and the `load_apk` / `unload_apk` tools behave identically no matter which client drives it.

**`mcpServers` JSON** — the de-facto standard, accepted by Claude Desktop, Cursor, Windsurf, Cline, Roo Code, LM Studio, Gemini CLI, and most others:

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

Only the file it goes in differs — e.g. Claude Desktop `claude_desktop_config.json`, Cursor `.cursor/mcp.json`, Windsurf `~/.codeium/windsurf/mcp_config.json`. Check your client's MCP docs for the exact path.

**VS Code / GitHub Copilot** uses `servers` + `type` (not `mcpServers`), in `.vscode/mcp.json`:

```json
{
  "servers": {
    "jadx-headless": {
      "type": "stdio",
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

**TOML-based CLIs** (Codex, Grok, …) use an `mcp_servers` table:

```toml
[mcp_servers.jadx-headless]
command = "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
args = []
```

## Remote mode (`--transport http`)

By default the server speaks stdio, so the MCP client spawns it as a local child process — client and jadx live on the same machine. To split them (client on machine B, jadx **and the APK** on machine A), run the server with the **Streamable HTTP** transport and point a remote MCP client at the URL:

```bash
# on machine A (where jadx runs and the APK lives)
./bin/jadx-headless-mcp --transport http --host 0.0.0.0 --port 8080 --allowed-host a.example.com
```

| Flag | Default | Meaning |
|---|---|---|
| `--transport <stdio\|http>` | `stdio` | serve Streamable HTTP instead of stdio |
| `--host <addr>` | `127.0.0.1` | bind address; use `0.0.0.0` to accept connections from other machines |
| `--port <n>` | `8080` | listen port |
| `--path <p>` | `/mcp` | endpoint path (serves POST/GET/DELETE) |
| `--allowed-host <h>` | — | extra `Host` header value accepted by the DNS-rebinding check (repeatable). Pass the hostname/IP clients use to reach machine A |
| `--no-dns-rebinding-protection` | off | disable `Host`/`Origin` validation (trusted networks / behind a reverse proxy only) |

The endpoint is `http://<host>:<port><path>` (default `http://127.0.0.1:8080/mcp`). Point any Streamable-HTTP-capable MCP client at it — e.g. Claude Code:

```bash
claude mcp add --transport http jadx-headless http://a.example.com:8080/mcp
```

**The APK always lives on the jadx host.** `load_apk` resolves its `path` with `File(path)` on the machine running the server (A) — a remote client only sends a path string that must be valid on A. Copy the APK to A first, then call `load_apk` with the A-side path. It's still one APK per process; multiple HTTP sessions share the single loaded APK.

**Security:**

- **DNS-rebinding protection is on by default** and only allows loopback `Host` headers, so a remote client reaching `a.example.com` is rejected with `403` unless you add `--allowed-host a.example.com` (or disable the check). This deliberately stops a malicious web page from driving your server.
- The endpoint has **no authentication**. Prefer binding to `127.0.0.1` and reaching it over an SSH tunnel, or put it behind a reverse proxy / VPN that adds auth. Don't expose `0.0.0.0` on an untrusted network.

## Architecture

```
Claude Code  ──stdio | Streamable-HTTP JSON-RPC──>  jadx-headless-mcp (JVM)
                                          │
                                          ├── MCP Kotlin SDK (stdio / Ktor CIO HTTP server)
                                          │
                                          └── SessionHolder
                                                 │  (Mutex-serialized load/unload)
                                                 └── JadxSession
                                                        └── JadxDecompiler (jadx-core)
```

Lazy decompilation: only `get_class_source` and `get_smali_of_class` (and `get_method_by_name` / `code` search) trigger per-class decompilation. Index building at startup is metadata-only.

**Important:** `max_bytes` truncates the response **after** jadx finishes materializing the class — it does **not** stop a slow decompile early. Fat obfuscated classes (e.g. large ViewModels) previously could block the whole MCP process for tens of minutes until the client’s multi-hour tool ceiling. As of the decompile-timeout fix:

- Server hard timeout: `--decompile-timeout-ms` (default **90000**)
- On timeout the tool returns an `// ERROR: ... timed out ...` banner immediately
- Prefer `get_class_summary` + `get_method_by_name` for large classes

## Memory tuning

The launcher sets `-XX:MaxRAMPercentage=60.0` by default. Override via `JAVA_OPTS`:

```bash
JAVA_OPTS="-Xmx8g" ./bin/jadx-headless-mcp
```

## Upstream tracking

| Dependency | Mechanism |
|---|---|
| `io.github.skylot:jadx-core` and friends | Dependabot weekly (opens PRs grouped under "jadx"); CI verifies the build still passes |
| `io.modelcontextprotocol:kotlin-sdk` | Dependabot weekly (grouped under "mcp-kotlin-sdk") |
| `org.jetbrains.kotlinx:*` | Dependabot weekly (grouped under "kotlinx") |
| GitHub Actions versions | Dependabot monthly |

Related but **not** auto-synced (different architecture; manual watch only):

- [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) — JADX GUI plugin
- [`zinja-coder/jadx-mcp-server`](https://github.com/zinja-coder/jadx-mcp-server) — Python adapter for the above
- [`mobilehackinglab/jadx-mcp-plugin`](https://github.com/mobilehackinglab/jadx-mcp-plugin) — alternative GUI plugin

If those projects ship a new tool worth porting, file an issue here.

## Acknowledgements

- [`skylot/jadx`](https://github.com/skylot/jadx) — the underlying decompiler
- [`modelcontextprotocol/kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk) — MCP server framework

## License

Apache-2.0.
