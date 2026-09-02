# jadx-headless-mcp

[English](README.md) | **简体中文**

直接基于 `jadx-core` 构建的 headless MCP 服务器，用于 Android APK 静态分析。无需 JADX GUI、无需 Python 适配器、无需安装插件 —— 单个 JVM 进程通过 stdio 跑 MCP 协议。

## 为什么做这个

jadx 由两部分组成：`jadx-core` 反编译引擎，和包在外面给人手点的 GUI。

让有能力的 LLM 直接调 `jadx-core`，GUI 那层就没了存在的理由。模型走同一棵类树、解同样的 xref、读同样的源码，全程 headless、可脚本化。模型越来越强，Java 层的逆向越来越能由它端到端推进，不再靠逆向工程师手动往前。

于是 MCP 丢掉 GUI，把 `jadx-core` 以约 26 个接口经 stdio 暴露。

## 工具列表

### 会话管理
| 工具 | 说明 |
|---|---|
| `status` | 当前是否已加载 APK；返回路径、类数、资源数、加载耗时 |
| `load_apk` | 加载 APK / DEX / JAR（替换当前已加载的） |
| `unload_apk` | 释放当前 APK 并回收内存 |

### 静态分析（需要先加载 APK）
| 工具 | 说明 |
|---|---|
| `get_app_info` | 包名、版本、minSdk/targetSdk、权限 |
| `get_android_manifest` | 完整的解码后 AndroidManifest.xml |
| `get_main_activity_class` | LAUNCHER Activity 的 FQN |
| `list_classes` | 分页的类 FQN 列表 |
| `search_classes_by_keyword` | 按 FQN 子串搜索类 |
| `get_class_source` | 反编译的 Java 源码 |
| `get_smali_of_class` | smali 反汇编 |
| `get_methods_of_class` | 类的方法签名列表 |
| `get_method_by_name` | 单个方法的源码 |
| `get_fields_of_class` | 字段列表（含类型） |
| `search_method_by_name` | 全局方法名搜索 |
| `get_xrefs_to_class` | 引用该类的位置 |
| `get_xrefs_to_method` | 调用该方法的位置 |
| `get_xrefs_to_field` | 引用该字段的位置 |
| `get_strings` | 字符串资源（可按子串过滤） |
| `list_resource_files` | 所有资源文件名 |
| `get_resource_file` | 资源文件内容（文本或 base64） |

## 构建

需要 JDK 17+。

```bash
./gradlew installDist
```

产物：`build/install/jadx-headless-mcp/bin/jadx-headless-mcp`（Windows 下还有 `.bat`）。

如果要打可移植 fat jar（单文件）：

```bash
./gradlew shadowJar
# build/libs/jadx-headless-mcp-<version>-all.jar
```

> **不想自己构建？** 每个 [GitHub Release](../../releases) 都附带预构建产物（fat jar + 发行 zip）—— 下载后直接让 MCP 配置指向它即可。

## 运行

空启动（之后通过 `load_apk` 工具按需加载 APK）：

```bash
./build/install/jadx-headless-mcp/bin/jadx-headless-mcp
```

或启动时直接加载：

```bash
./build/install/jadx-headless-mcp/bin/jadx-headless-mcp \
  --apk /path/to/app.apk \
  --max-source-bytes 200000
```

服务器通过 stdio 跑 MCP 协议。日志全部走 stderr。

## MCP 客户端配置

`jadx-headless` 是标准 stdio MCP 服务器，任何支持 MCP 的客户端都能驱动它：Claude Code、Claude Desktop、Cursor、Windsurf、Cline、Roo Code、VS Code / Copilot、LM Studio、Zed、Codex、Gemini CLI 等。Claude Code 接入最省事；其余客户端加一小段配置即可，见 [其它 MCP 客户端](#其它-mcp-客户端)。

### Claude Code

#### 最快：用 CLI 注册

一行命令，无需手改 JSON。`-s user` 写进全局 `~/.claude.json`；默认的 `local` scope 只对当前项目生效：

```bash
# installDist 启动脚本
claude mcp add jadx-headless -s user -- C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat

# ……或可移植 fat jar（单文件，需 java 在 PATH 上）
claude mcp add jadx-headless -s user -- java -jar C:/tools/jadx-headless-mcp-<version>-all.jar
```

之后让 AI 调用 `load_apk` 加载你想分析的 APK。要切换到另一个 APK 时直接再调 `load_apk`，或先 `unload_apk` 释放内存 —— **全程不用改配置**。

#### 或手改配置文件

注册一个 entry 即可，运行时按需切换 APK：

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

要指向可移植 fat jar：

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

如果你只分析单个 APK 且希望启动后立即可用，用 `args` 附上 APK 路径：

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

> **注意：** `java -jar` 会跳过启动脚本里烘焙的默认 JVM 参数（如 `-XX:MaxRAMPercentage=60`）。stdout 两种方式都干净 —— slf4j-simple 日志走 stderr，`Main.kt` 也把 `System.out` 重定向到了 stderr —— 但如果要限制内存，得自己通过 `JAVA_OPTS` / `-XX` 传。installDist 启动脚本则保留这些默认值。

### 其它 MCP 客户端

把客户端指向同一个启动脚本（或用 `java -jar` 跑 fat jar）即可，各家只是外层配置格式不同。任何客户端都只需两样：`command` 填启动脚本（或 `java`），用 fat jar 时 `args` 填 `["-jar", "<path>-all.jar"]`。`--apk` / `--max-source-bytes` 和 `load_apk` / `unload_apk` 工具在哪个客户端下行为都一致。

**`mcpServers` JSON**：事实标准，Claude Desktop、Cursor、Windsurf、Cline、Roo Code、LM Studio、Gemini CLI 等大多数客户端都吃这套：

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

区别只在放进哪个文件，比如 Claude Desktop 的 `claude_desktop_config.json`、Cursor 的 `.cursor/mcp.json`、Windsurf 的 `~/.codeium/windsurf/mcp_config.json`。确切路径查各客户端的 MCP 文档。

**VS Code / GitHub Copilot** 用的是 `servers` + `type`（不是 `mcpServers`），写在 `.vscode/mcp.json`：

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

**TOML 系 CLI**（Codex、Grok 等）用 `mcp_servers` 表：

```toml
[mcp_servers.jadx-headless]
command = "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
args = []
```

## 远程模式（`--transport http`）

默认走 stdio，客户端会把服务器当子进程本地拉起 —— 客户端和 jadx 在同一台机器。要把两者拆开（客户端在 B 机、jadx **和 APK** 在 A 机），用 **Streamable HTTP** 传输启动，再让远程 MCP 客户端连这个 URL：

```bash
# 在 A 机（跑 jadx、APK 也在这台）
./bin/jadx-headless-mcp --transport http --host 0.0.0.0 --port 8080 --allowed-host a.example.com
```

| 参数 | 默认 | 含义 |
|---|---|---|
| `--transport <stdio\|http>` | `stdio` | 用 Streamable HTTP 代替 stdio |
| `--host <addr>` | `127.0.0.1` | 监听地址；填 `0.0.0.0` 才能接受其它机器连入 |
| `--port <n>` | `8080` | 监听端口 |
| `--path <p>` | `/mcp` | 端点路径（POST/GET/DELETE） |
| `--allowed-host <h>` | — | DNS-rebinding 校验额外放行的 `Host` 头（可重复）。填客户端访问 A 机用的主机名/IP |
| `--no-dns-rebinding-protection` | 关 | 关闭 `Host`/`Origin` 校验（仅可信网络 / 反代之后用） |

端点是 `http://<host>:<port><path>`（默认 `http://127.0.0.1:8080/mcp`）。任何支持 Streamable HTTP 的 MCP 客户端都能连，比如 Claude Code：

```bash
claude mcp add --transport http jadx-headless http://a.example.com:8080/mcp
```

**APK 永远在跑 jadx 的那台机器上。** `load_apk` 是在服务端（A 机）用 `File(path)` 解析路径的 —— 远程客户端只是发一个路径字符串，这个路径必须在 A 上有效。先把 APK 拷到 A，再用 A 侧路径调 `load_apk`。仍然是「一进程一个 APK」；多个 HTTP 会话共享同一个已加载 APK。

**安全提示：**

- **DNS-rebinding 保护默认开启**，只放行 loopback 的 `Host` 头，所以远程客户端访问 `a.example.com` 会被 `403` 拒绝，除非加 `--allowed-host a.example.com`（或关掉校验）。这是故意的 —— 防止恶意网页驱动你的服务器。
- 端点**没有鉴权**。优先绑 `127.0.0.1` 再用 SSH 隧道访问，或放到带鉴权的反代 / VPN 之后。别在不可信网络上裸露 `0.0.0.0`。

## 架构

```
Claude Code  ──stdio | Streamable-HTTP JSON-RPC──>  jadx-headless-mcp (JVM)
                                          │
                                          ├── MCP Kotlin SDK (stdio / Ktor CIO HTTP 服务器)
                                          │
                                          └── SessionHolder
                                                 │  (用 Mutex 串行化 load/unload)
                                                 └── JadxSession
                                                        └── JadxDecompiler (jadx-core)
```

懒解析：只有 `get_class_source` / `get_smali_of_class` 会触发单类反编译。启动时只构建元数据索引。

## 内存调优

启动脚本默认 `-XX:MaxRAMPercentage=60.0`。要覆盖可用 `JAVA_OPTS`：

```bash
JAVA_OPTS="-Xmx8g" ./bin/jadx-headless-mcp
```

## 上游跟踪

| 依赖 | 同步方式 |
|---|---|
| `io.github.skylot:jadx-core` 等 | Dependabot 每周扫描（PR 分组到 "jadx"）；CI 验证编译仍然通过 |
| `io.modelcontextprotocol:kotlin-sdk` | Dependabot 每周扫描（分组 "mcp-kotlin-sdk"） |
| `org.jetbrains.kotlinx:*` | Dependabot 每周扫描（分组 "kotlinx"） |
| GitHub Actions 版本 | Dependabot 每月扫描 |

下列项目相关但**不会自动同步**（架构不同，仅作人工关注）：

- [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) —— JADX GUI 插件
- [`zinja-coder/jadx-mcp-server`](https://github.com/zinja-coder/jadx-mcp-server) —— 上面那个插件的 Python 适配器
- [`mobilehackinglab/jadx-mcp-plugin`](https://github.com/mobilehackinglab/jadx-mcp-plugin) —— 另一套 GUI 插件实现

如果它们出了值得移植的新工具，欢迎来本项目开 issue。

## 致谢

- [`skylot/jadx`](https://github.com/skylot/jadx) —— 底层反编译器
- [`modelcontextprotocol/kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk) —— MCP 服务器框架

## License

Apache-2.0.
