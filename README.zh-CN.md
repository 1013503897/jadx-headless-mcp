# jadx-headless-mcp

[English](README.md) | **简体中文**

直接基于 `jadx-core` 构建的 headless MCP 服务器，用于 Android APK 静态分析。无需 JADX GUI、无需 Python 适配器、无需安装插件 —— 单个 JVM 进程通过 stdio 跑 MCP 协议。

## 为什么做这个

现有的 JADX MCP 项目（[`zinja-coder/jadx-mcp-server`](https://github.com/zinja-coder/jadx-mcp-server)、[`mobilehackinglab/jadx-mcp-plugin`](https://github.com/mobilehackinglab/jadx-mcp-plugin)）都需要打开 `jadx-gui` 并加载插件才能工作。这在下列场景下很别扭：

- 批量 APK 分析流水线
- 无显示器的 CI / Docker / 服务器部署
- 多 APK 并行（GUI 单实例基线就要 300–500 MB）
- 高频工具调用（Python ↔ HTTP ↔ JVM 三跳累积可观）

本项目直接通过 `jadx-core` library 加载 APK，作为单个 MCP stdio 服务器暴露 20 个工具。

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

如果要打 fat jar：

```bash
./gradlew shadowJar
# build/libs/jadx-headless-mcp-<version>-all.jar
```

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

## Claude Code MCP 配置

注册一个 entry 即可，运行时按需切换 APK，**不用改配置**：

```json
{
  "mcpServers": {
    "jadx-headless": {
      "command": "C:/tools/jadx-headless-mcp/bin/jadx-headless-mcp.bat"
    }
  }
}
```

之后让 AI 调用 `load_apk` 加载你想分析的 APK。要切换到另一个 APK 时直接再调 `load_apk`，或先 `unload_apk` 释放内存。

如果你只分析单个 APK 且希望启动后立即可用：

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

## 架构

```
Claude Code  ──stdio JSON-RPC──>  jadx-headless-mcp (JVM)
                                          │
                                          ├── MCP Kotlin SDK (stdio 服务器)
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

## 已知局限

- 单进程同时只能持有一个 APK（用 `load_apk` 切换）
- 只读，暂未实现 rename / 修改类工具
- 仅 Java 层反编译；Unity / IL2CPP 场景请配合 IL2CPP dump 使用
- AndroidManifest 用正则解析（够查 activity / permission，不是完整 XML 解析器）

## 致谢

- [`skylot/jadx`](https://github.com/skylot/jadx) —— 底层反编译器
- [`modelcontextprotocol/kotlin-sdk`](https://github.com/modelcontextprotocol/kotlin-sdk) —— MCP 服务器框架

## License

Apache-2.0.
