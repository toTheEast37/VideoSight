# VideoSight：视频上传与文字解析学习工程

通过逐步重建 DOVideo 的能力，学习需求边界、实现、验收和失败处理。当前提供视频上传、后台文字识别和基于原文证据的单视频单轮问答。

## 当前状态

- 已实现分片上传、刷新后重选原文件续传、按轮补传、请求幂等和视频资产查询/读取。
- 已实现独立解析任务：FFmpeg 提取音频与抽样画面，百炼 ASR 转写语音，画面默认由 Tesseract 识别，也可配置百炼 `qwen3.5-ocr`；结果逐项保存。
- 资料库中选择「提问」，可以针对已解析的单个视频提问。系统按子问题审查原文，证据不足时只补查一次。回答附语音或画面原文引文，点击时间定位视频。问答向量存现有 MySQL，在 Java 中完成加权排序。
- MySQL 保存上传和解析事实，解析处理单元同时保存消息派发状态；Redis 保存临时上传进度，MinIO 保存视频和识别输入，RocketMQ 派发处理单元。数据库短事务领取任务，长耗时识别在事务外执行。
- 真实本机服务与浏览器已完成上传和约 8 秒测试视频的完整解析、刷新及应用重启后读取。识别质量和大文件性能的证据范围见任务记录。
- 通用配置默认关闭上传、解析和问答。通过被忽略的本机 dev 配置启用所需能力，问答可读取已保存的解析结果。
- 提交和推送须有用户明确指令；私密配置、运行数据、媒体和构建产物不入库。

## 分支协作

远程仓库：[toTheEast37/VideoSight](https://github.com/toTheEast37/VideoSight)。本地提交记录与远程分支保留完整项目历史。

- `main`：已验证的稳定版本。
- `dev`：功能集成分支。
- `codex/video-upload` 等功能分支：从 `dev` 开始开发，通过 Pull Request 合回 `dev`；检查通过后，再将 `dev` 合回 `main`。

GitHub Actions 在 `main`/`dev` 的推送及面向这两个分支的 Pull Request 上执行默认 Java 构建测试和前端检查，不读取本机私密配置，也不执行需要真实 Redis 的显式集成测试。自动检查不替代真实视频及外部服务验收，验证边界见任务记录。

## 运行准备

需要 JDK 17；工程自带 Maven Wrapper，无需全局安装 Maven。首次构建需要联网。以下命令从仓库根目录执行。

首次创建私密配置，已有文件则保留：

```powershell
if (!(Test-Path 'config/application-dev.yaml')) {
    Copy-Item 'config/application-dev.example.yaml' 'config/application-dev.yaml'
}
```

真实连接信息只填入被 Git 忽略的 `config/application-dev.yaml`。示例文件只有占位值，不要向示例或通用配置填密码。

启用真实上传前：

1. 准备本项目独立的 MySQL 数据库，避免复用其他项目的业务库。Flyway 在独立库创建上传与解析表，不自动修改黑马点评表。
2. 在 dev 配置填写 MySQL、Redis 和 MinIO 的连接信息。MinIO 使用本项目独立存储桶，应用首次初始化上传时会检查并按需创建该桶。
3. 确认服务可用后，将 dev 文件中的 `app.upload.enabled` 改为 `true`。未完成配置时保持 `false`。

本轮已按用户指定来源复制 MySQL/Redis 私密配置到本地 dev 文件，并创建独立数据库 `vibeteaching`。2026-11-16 接通本机 Redis 6379 与 MinIO 9000，在独立桶 `vibeteaching-videos` 完成真实上传；本机 dev 上传开关已开启。MinIO 管理页面使用 9001 端口，后端使用 9000 接口；凭据仍仅在忽略的 dev 文件中。

## 准备本地解析依赖

下列脚本在 Windows、JDK 17 环境下运行；下载与数据都放在被忽略的 `work/`，不修改全局 PATH。首次下载需要网络；本机已准备好工具，可离线重新校验媒体工具。以下命令已在本机实际执行：

```powershell
.\scripts\SetupMediaTools.ps1 -Offline
.\scripts\SetupRocketMq.ps1
.\scripts\StartRocketMq.ps1
```

首次准备媒体工具时去掉 `-Offline`。脚本会核对固定摘要并便携提取 FFmpeg/FFprobe、Tesseract 和中英文语言数据。

本地 RocketMQ 仅监听 `127.0.0.1` 的 9876、10911 和 10909，脚本创建 `videosight-parse` 主题。启动器通过项目内 SPI 关闭本实验不需要的 HA 复制服务；这是单 Broker 本地开发配置，不适用于集群部署。停止当前项目的队列进程：

```powershell
.\scripts\StopRocketMq.ps1
```

在私密 dev 文件填写 `app.parse` 的配置后开启 `enabled`：

- `nameserver`：本机使用 `127.0.0.1:9876`。
- `ffmpeg-command`、`ffprobe-command`：指向 `work/tools/media/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/` 下对应 EXE 的绝对路径。
- `ocr-provider`：默认 `tesseract`，使用 `ocr-command` 指定的本机工具。设为 `qwen` 时使用百炼 `qwen3.5-ocr`，默认复用 `app.qa` 的地址和密钥，也可单独设置 `ocr-base-url`、`ocr-api-key`。
- `asr-url`、`asr-api-key`：百炼非流式 ASR 接口与密钥；`asr-model` 使用 `qwen-audio-3.1-asr-flash`。每片 WAV 直接编码后提交，云端不需要访问本机 MinIO。

原视频解析暂限 256 MiB、30 分钟；音频约 60 秒一片，画面每 5 秒抽样，最多 360 帧。固定抽样会漏掉短暂画面，OCR 可能误识代码符号；时间标记不是句级字幕对齐。无音轨会跳过 ASR。云端是否已处理无法确认时，任务停止自动重提，需要用户明确接受可能重复计费后重试。

切换模型后，旧解析结果仍可在页面查看。选择「用当前配置重新解析」会从原视频生成新任务，重新调用 ASR 和 OCR。问答从已保存的画面转录生成用于向量化的检索文本，证据引用沿用原始转录和抽帧时间。

## 构建与检查

```powershell
.\mvnw.cmd --batch-mode verify
node --test src/test/js/parse-core.test.mjs src/test/js/upload-core.test.mjs
```

默认 Java 检查不要求外部服务；解析阶段的实际总数与显式媒体工具集成检查见 [任务与证据](docs/tasks.md)。前端逻辑与接口模拟共 21 项通过，它们不能代替真实模型和完整浏览器验收。上传阶段另已执行下面的真实 Redis 集成测试，验证生产 Lua、重复片、冲突、TTL 续期及缺键拒绝；它仅操作随机测试会话的两键，并在结束时清理：

```powershell
.\mvnw.cmd --offline --batch-mode '-Dupload.redis.integration=true' '-Dtest=RedisUploadProgressStoreIntegrationTest' test
```

该命令从本地 dev 文件读取 Redis 配置，要求真实服务可用；上传阶段 1 项通过。浏览器另使用约 12.5 MiB、3 片的真实 WebM 完成上传、播放和重启读取验证，具体证据及限制见任务清单。

启动已构建的 JAR，显式激活 dev；本轮检查使用 18080 端口：

```powershell
java -jar .\target\vibeteaching-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --server.port=18080
```

打开 [本机页面](http://127.0.0.1:18080/)。上传后，从资料库点击“解析 / 查看文字”。上传需要 MySQL、Redis、MinIO；新解析还需要本地工具、百炼配置和 RocketMQ。Broker 暂时不可用时，配置齐备的任务仍会持久排队，恢复后继续。解析开关关闭仍可读取已保存历史。前台进程用 Ctrl+C 停止；Windows 重新打包前应先停止占用该 JAR 的进程。

```powershell
Invoke-WebRequest 'http://127.0.0.1:18080/actuator/health' -Headers @{ Accept = 'application/json' } -UseBasicParsing
```

默认关闭上传时，该健康状态只表示基础工程可用，不能证明数据库、Redis、MinIO 已连通。

## 启用单视频问答

在被忽略的 `config/application-dev.yaml` 中启用 `app.qa.enabled`，并填写 `app.qa.base-url` 和 `app.qa.api-key`。百炼业务空间的兼容地址以 `/compatible-mode/v1` 结尾。沿用上传启用后的 MySQL 连接，启动时由 Flyway 建立片段向量表。

在资料库选择有已保存解析结果的视频，点击「提问」。首次提问按需生成并保存片段向量，之后可复用。问题和回答按单轮处理。

## 召回实验

在仓库根目录用 Node.js 执行，本次用 Node.js 24 验证。实时运行前，从本地私密 dev 配置向当前进程提供 `DASHSCOPE_API_KEY`。如果使用北京业务空间的专属域名，同时设置 `VIDEOSIGHT_EMBEDDING_ORIGIN` 为该 HTTPS 域名，不带接口路径。

```powershell
node scripts/retrieval-experiment.mjs
node scripts/retrieval-experiment.mjs --cached
node scripts/retrieval-experiment.mjs --rerank
node scripts/retrieval-experiment.mjs --rerank-all
```

第一条提交固定的模拟 ASR/OCR 文本与问题，保存百炼向量及逐题排名到 `work/retrieval-experiment/`。第二条离线回放。`--rerank` 复用已保存的向量，每题重排前 10 段，输出 `rerank-results.json`。`--rerank-all` 在同一轮分别重排前 10 段和全部 24 段，每题发出两次请求，输出 `rerank-comparison.json`。实验方法和实际结果分别见[技术记录](docs/technical-notes.md#召回对照实验)与[任务与证据](docs/tasks.md#视频片段召回实验2026-11-23)。

## 文件与状态的位置

- `src/main/resources/application.yaml`：通用非敏感配置与开发暂用上传参数。
- `config/application-dev.yaml`：本地连接信息，不入库、不打入 JAR；从仓库根目录启动才能使用默认外部配置路径读取。
- `src/main/resources/db/migration/`：唯一的正式数据库结构来源。
- `src/main/resources/static/`：同源页面，无独立前端构建。
- `work/`、`target/`：忽略的临时文件、验证输出和构建产物。

## 文档入口

| 文件 | 内容 |
| --- | --- |
| [需求与验收](docs/requirements-v0.1.md) | 已确认范围、开发暂用边界与验收标准 |
| [技术记录](docs/technical-notes.md) | 接口、状态、幂等、存储与失败边界 |
| [任务与证据](docs/tasks.md) | 实际改动、检查结果与尚未完成的验证 |
| [协作规则](AGENTS.md) | 沟通、配置保护和 Git 规则 |
