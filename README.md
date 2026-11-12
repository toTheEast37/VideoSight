# DOVideo-AI 学习重建

通过逐步重建一个视频学习助手，练习借助 AI 完成需求、开发、验收、代码审查和文档维护，并能解释关键实现。后端主要使用 Java。

## 当前阶段

- 2026-11-12：完成首版需求草案、任务拆分和 T01 工程初始化。
- 已确认：本地单人使用、无需登录；内置固定字幕、允许修改学习目标；首版验收需要真实模型生成。
- 补充确认：首版独立总结与历史查看，追问和会话记忆放下一阶段。使用本机 MySQL 和阿里云百炼，私密配置放在被 Git 忽略的 `application-dev.yaml`。
- 当前已有可启动的 Spring Boot 工程，已验证构建、两种启动入口和健康接口；字幕页面、总结、数据库保存和模型调用尚未实现。
- Git 用于本地版本管理。按用户要求，当前不推送 GitHub，远程地址暂不配置。

## 文档入口

| 文件 | 负责维护的内容 |
| --- | --- |
| [首版需求](docs/requirements-v0.1.md) | 产品范围、用户行为、验收标准和假设 |
| [任务清单](docs/tasks.md) | 首项任务的子任务、验收方法、学习目标与进度 |
| [技术方案草案](docs/technical-notes.md) | 实现建议、理由和待定技术选择 |
| [协作规则](AGENTS.md) | AI 和人协作时遵守的长期规则 |

需求、技术选择和执行进度分别在对应文件中维护。README 仅提供定位、入口和运行说明。

## 开发与验证

需要 JDK 17。工程自带 Maven Wrapper，无需预装 Maven；首次使用需要联网下载 Maven 和依赖。以下 PowerShell 命令已在本机验证，均从仓库根目录执行。

首次准备本地配置；如果文件已经存在，保留原文件：

```powershell
if (!(Test-Path 'config/application-dev.yaml')) {
    Copy-Item 'config/application-dev.example.yaml' 'config/application-dev.yaml'
}
```

本轮已生成一份无密钥的本地配置。`config/application-dev.yaml` 被 Git 忽略，并位于 JAR 外；以后私密连接信息和 API key 只填入此文件。T01 无需配置或连接 MySQL、百炼。通用配置在 `src/main/resources/application.yaml`，默认仅监听 `127.0.0.1:8080`。

检查构建工具并构建可执行 JAR：

```powershell
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode clean verify
```

当前构建没有自动化测试，`verify` 成功表示编译和打包通过；实际启动及 HTTP 检查证据见任务清单。产物为 `target/vibeteaching-0.0.1-SNAPSHOT.jar`，不入库。

开发启动，显式激活 dev 环境：

```powershell
.\mvnw.cmd --batch-mode spring-boot:run "-Dspring-boot.run.profiles=dev"
```

也可以运行已构建的 JAR，二者选择一个，避免占用同一个端口：

```powershell
java -jar .\target\vibeteaching-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

保持从仓库根目录启动，Spring Boot 才能按默认路径读取 `config/application-dev.yaml`。程序在前台运行，使用 Ctrl+C 停止。在另一个 PowerShell 终端检查：

```powershell
Invoke-WebRequest 'http://127.0.0.1:8080/actuator/health' -Headers @{ Accept = 'application/json' } -UseBasicParsing
```

预期 HTTP 200，正文 `{"status":"UP"}`。这个接口只说明当前工程的健康状态，不证明数据库或模型已连通；本轮尚无业务页面，访问根路径不是字幕页面验收。

使用同一仓库管理全部内容：规划文档、工程初始化分别提交；建立代码基线后的同一功能跟进，可将代码、测试和对应文档一起提交。完整约定见 AGENTS.md。

## 后续方向

先完成字幕总结闭环，再按需求加入视频上传、语音转写、画面文字识别、后台任务、进度推送、检索和 Agent。具体范围以需求文档为准。
