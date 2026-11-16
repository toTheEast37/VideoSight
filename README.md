# VideoSight：视频上传学习工程

通过逐步重建 DOVideo 的能力，学习需求边界、实现、验收和失败处理。当前先完成视频上传，字幕总结和 Agent 暂缓。

## 当前状态

- 已实现分片上传、刷新后重选原文件续传、按轮补传、请求幂等和视频资产查询/读取。
- MySQL 保存上传会话与资产记录，Redis 保存临时分片进度，MinIO 保存文件。尚未加入 MQ、登录、内容秒传或 Agent。
- Java 自动化测试与前端接口模拟检查已通过；真实 MySQL、Redis、MinIO 和浏览器已完成小视频联调，覆盖刷新续传、按轮补传及应用重启后读取。未验证 2 GiB 文件性能。
- 通用配置默认 `app.upload.enabled=false`；本机被忽略的 dev 配置已启用上传，连接本机三个服务。
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

1. 准备本项目独立的 MySQL 数据库，避免复用其他项目的业务库。Flyway 在独立库创建上传表，不自动修改黑马点评表。
2. 在 dev 配置填写 MySQL、Redis 和 MinIO 的连接信息。MinIO 使用本项目独立存储桶，应用首次初始化上传时会检查并按需创建该桶。
3. 确认服务可用后，将 dev 文件中的 `app.upload.enabled` 改为 `true`。未完成配置时保持 `false`。

本轮已按用户指定来源复制 MySQL/Redis 私密配置到本地 dev 文件，并创建独立数据库 `vibeteaching`。2026-11-16 接通本机 Redis 6379 与 MinIO 9000，在独立桶 `vibeteaching-videos` 完成真实上传；本机 dev 上传开关已开启。MinIO 管理页面使用 9001 端口，后端使用 9000 接口；凭据仍仅在忽略的 dev 文件中。

## 构建与检查

```powershell
.\mvnw.cmd --batch-mode verify
node --test src/test/js/upload-core.test.mjs
```

默认 Java 检查为 37 项通过、1 项真实 Redis 测试跳过；前端检查为 13 项逻辑与接口模拟通过。默认检查不要求外部服务。另已执行下面的真实 Redis 集成测试，验证生产 Lua、重复片、冲突、TTL 续期及缺键拒绝；它仅操作随机测试会话的两键，并在结束时清理：

```powershell
.\mvnw.cmd --offline --batch-mode '-Dupload.redis.integration=true' '-Dtest=RedisUploadProgressStoreIntegrationTest' test
```

该命令从本地 dev 文件读取 Redis 配置，要求真实服务可用；本轮 1 项通过。浏览器另使用约 12.5 MiB、3 片的真实 WebM 完成上传、播放和重启读取验证，具体证据及限制见任务清单。

启动已构建的 JAR，显式激活 dev；本轮检查使用 18080 端口：

```powershell
java -jar .\target\vibeteaching-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev --server.port=18080
```

打开 `http://127.0.0.1:18080/`。本机三个服务运行且 dev 已启用时可上传；未启用上传时，页面会提示配置服务。前台进程用 Ctrl+C 停止；Windows 重新打包前应先停止占用该 JAR 的进程。

```powershell
Invoke-WebRequest 'http://127.0.0.1:18080/actuator/health' -Headers @{ Accept = 'application/json' } -UseBasicParsing
```

默认关闭上传时，该健康状态只表示基础工程可用，不能证明数据库、Redis、MinIO 已连通。

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
