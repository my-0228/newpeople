# 构建与验证环境说明（已实测）

- 日期：2026-09-18
- 用途：本文件是 `2026-09-18-ai-qa-rag-design.md` 与 `2026-09-18-deepseek-agent-design.md` **共用的**构建/验证环境事实源，避免两处重复描述产生漂移
- 状态：以下每一条都**实际执行过**，不是推断

---

## 1. 可用的构建命令（已验证 BUILD SUCCESS）

```powershell
$env:JAVA_HOME = "D:\PyCharm 2025.1.2\jbr"          # JDK 21.0.7，必须有 javac
$mvn = "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.12-bin\5nmfsn99br87k5d4ajlekdq10k\apache-maven-3.9.12\bin\mvn.cmd"
$repo = "$env:USERPROFILE\.m2\repository"           # 可写的本地仓库

& $mvn -s docs\build\maven-settings.xml "-Dmaven.repo.local=$repo" -B compile
```

实测结果：`BUILD SUCCESS`，`Compiling 59 source files with javac [debug release 17]`，耗时 4.6 秒。

## 2. 三个必须绕开的坑（都实测踩过）

| # | 现象 | 根因 | 解法 |
|---|---|---|---|
| 1 | `AccessDeniedException: D:\mvnrepo\...` → `Internal error: java.io.UncheckedIOException` | Maven 的本地仓库被配置在 **`D:\mvnrepo`**（工作区之外），本会话沙箱不允许写入该路径，Maven 写 `*.lastUpdated` 跟踪文件失败 | 用 `-Dmaven.repo.local=$env:USERPROFILE\.m2\repository` 指向**可写**的仓库（该路径写权限实测通过） |
| 2 | `BUILD FAILURE`：`spring-boot-starter-parent:pom:3.2.5 (absent)` 但文件确实存在 | 该构件在 `_remote.repositories` 中记录为 `central`，而用户 `settings.xml` 的镜像 `<id>` 是 `aliyun`，离线模式下 id 不匹配被判为 absent；`-llr` 参数在 Maven 3.9 已被移除 | 用工作区内的 `.tmp-build/settings.xml`，把镜像 `<id>` 改成 `central`（见下） |
| 3 | `Error reading old mojo status ...inputFiles.lst: Input length = 1` | `target/maven-status` 是**旧构建（不同编码环境）**留下的，编码不符导致读取失败 | 编译前 `Remove-Item -Recurse -Force target\maven-status` |

### ⚠️ 坑 4（最重要）：不要用 JBR 25 构建本项目

| JDK | 结果 |
|---|---|
| IntelliJ 的 JBR **25.0.4** | ❌ 编译失败：`Faq::getStatus` 等 Lombok 生成的 getter/setter **全部找不到符号**。因为 Spring Boot 3.2.5 管理的 Lombok 版本不支持 JDK 25，注解处理静默失效 |
| PyCharm 的 JBR **21.0.7** | ✅ **BUILD SUCCESS** |

`-Dmaven.compiler.release=17` **不能**解决此问题 —— Lombok 是在 javac 进程内运行的，受 JDK 版本约束。

## 3. TLS 与网络（实测）

| 客户端 | 能否建 TLS | 说明 |
|---|---|---|
| `curl.exe` / .NET `SslStream` | ❌ `SEC_E_NO_CREDENTIALS`（schannel 凭据不可用） | 沙箱进程拿不到证书存储 |
| **Java（SunJSSE）** | ✅ 正常 | Maven 依赖下载、我的 API 探针都成功 |

**结论**：
- 调用**外部 API**（DashScope / DeepSeek）以及**构建下载依赖** → 一律走 Java
- `curl` 仅可用于 **localhost 明文**（如 `curl http://localhost:8080/api/ai/chat`）
- 沙箱**允许**写入 `~/.m2/repository`；**拒绝**写入 `D:\mvnrepo`

## 4. M0 外部能力探针结果（已实测）

用 JDK 21 写的探针（`java.net.http.HttpClient`）实测：

| 探针 | 结果 |
|---|---|
| `POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings`，`model=text-embedding-v3`，2 条输入，`dimensions=1024` | ✅ **HTTP 200**，`data[0].embedding` 长度 **1024**，响应 43578 字节。现有阿里 key **具备 Embedding 权限** |
| `POST https://api.deepseek.com/v1/chat/completions`，`model=deepseek-v4-flash`，携带 1 个 `tools` 定义 | ✅ **HTTP 200**，`finish_reason="tool_calls"`，`tool_calls[0].function.name="query_campus_data"`，`arguments="{\"entity\": \"dormitory\"}"`（**注意：是 JSON 字符串，需二次解析**），`usage.total_tokens=358` |

## 5. 其他环境事实

| 项 | 值 |
|---|---|
| MySQL | 3306 端口在监听 ✅；`mysql` 客户端不在 PATH（验证用 JDBC 小工具或应用自身） |
| 8080 端口 | 空闲 |
| 磁盘 | `Get-PSDrive`/`Get-CimInstance` 被沙箱屏蔽（返回 0/0），**不是真的磁盘满**，勿据此判断 |
| `~/.m2/repository` | 642.7 MB / 11802 文件 / 2853 个 `_remote.repositories` |
| `D:\mvnrepo` | 160 MB（Maven 默认指向此处的仓库，不可写） |
| 其他 JDK | `JAVA_HOME` 现指向 **JDK 1.8.0_121**（不满足项目 `java.version=17`）；机器上未见其他 17/21 JDK |
