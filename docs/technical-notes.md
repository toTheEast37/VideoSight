# 视频上传与文字解析技术记录

更新日期：2026-11-19。产品要求以 requirements-v0.1.md 为准；以下区分当前代码、暂用参数和实际验证，不将短视频完整链路或独立工具检查推断为全部视频解析验收通过。

## 当前选择与边界

- Java 17、Spring Boot 3.5.16、Maven 3.9.16 与 Wrapper 3.3.4 保留 T01 基线。
- Spring JDBC + Flyway + MySQL 保存上传会话、视频资产和解析任务检查点；Redis 保存临时上传分片进度；MinIO SDK 9.0.4 保存原视频和持久识别输入。
- MinIO SDK 的 Maven 依赖需要显式 okhttp-jvm 5.5.1，并将 Kotlin 版本对齐 SDK 所需的 2.1.21。编译、HTTP 替身及本机真实 MinIO 读写均已通过；本机服务端为用户启动的 RELEASE.2025-07-23T15-54-02Z。
- 同源静态页面，无前端框架或独立打包。浏览器以文件切片逐段读取，不将整段视频加载到一个 ArrayBuffer。
- 页面及文档的项目显示名为 VideoSight；已有包名、构建产物、数据库、存储桶和浏览器存储键保留现有技术标识，继续复用已保存资产和续传定位。
- 默认 `app.upload.enabled=false`、`app.parse.enabled=false`。上传启用后才创建数据源、Flyway、Redis 和对象存储组件；两个开关均启用后才派发解析消息并连接消费者。基础页面启动不要求提供密钥，连接信息仅在被忽略的 `config/application-dev.yaml`。
- 单后端实例、本地单人使用；本轮增加 RocketMQ Java 客户端 5.3.4。上传仍以固定条带锁协调同会话关键操作；解析以数据库短事务领取阶段，不引入 Redisson、用户表或内容秒传，不承诺跨实例协调。

## 标识与数据位置

| 标识或内容 | 位置与职责 |
| --- | --- |
| requestKey | 浏览器在初始化请求前持久保存；upload_session.request_key 唯一，识别同一次初始化意图 |
| uploadId | 后端生成；上传表主键，分片、查询、完成和资产关联均用它 |
| assetId | 视频资产表主键；长期找回视频，与初始化请求键不同 |
| bucket + objectKey | 资产表保存对象位置，MinIO 保存真实字节；本轮通过后端流式读取，不持久保存签名 URL |
| 文件与请求定位信息 | 浏览器 localStorage 只保存定位与核对信息；刷新后仍须用户重选原文件 |

正式结构由 Flyway 的 V1（上传）、V2（解析初始结构）和 V3（派发状态并入处理单元）按序维护；V3 文件为 `V3__merge_parse_dispatch_into_units.sql`，已执行的 V1/V2 不改写。此前讨论中的示意 SQL 不作为独立建表入口。

upload_session 保存文件信息、分片方案、指纹、状态、到期时间和待清理标记。video_asset 保存最终视频摘要与对象位置，upload_id 唯一且关联上传表。不同请求再次上传同样内容不会自动复用资产，摘要没有唯一约束。

## 文件身份与分片校验

当前指纹算法为 SHA256_CHUNK_LIST_V1：按会话 chunkSize 分片，逐片计算 SHA-256，按顺序拼接各片的小写十六进制摘要，再对该字符串的 UTF-8 字节计算 SHA-256。它用于绑定原文件与分片方案，不是整文件 SHA-256。

前端保存原会话的分片大小；刷新后服务端默认分片大小发生变化，也先按旧大小核对候选会话。仅凭文件名、大小不能证明是原文件。

后端校验每片实际长度与 X-Chunk-SHA256；完成时复核分片摘要列表、逐片重新读取并校验内容，另计算最终文件真实 SHA-256，保存到 video_asset.sha256。

上传阶段按声明 MIME 接收 MP4/WebM/MOV，不进行容器解码、转码或编码兼容性验证。解析阶段另外调用 FFprobe 检查实际轨道与时长，再由 FFmpeg 解码。浏览器是否能播放还取决于实际编码，下载读取不依赖播放能力。

## Redis 两键结构与 TTL

```text
upload:{U}:meta   Hash：totalChunks、fileSize、chunkSize、fingerprint、receivedCount、part:序号的摘要
upload:{U}:parts  Set：已成功存储的分片序号
```

不在 Hash 中原生嵌套 Set；根据 uploadId 拼键即可关联。新会话尚无成功片时没有空 Set 是正常情况；已有进度的键丢失、摘要缺失或计数不一致不能按零进度继续。

Lua 同时校验元信息、SCARD、摘要数量和序号，并在同一次执行中记录成功片及两键的绝对到期时间。成功片写入后显式续期，期限与 MySQL 会话规则对齐。2026-11-16 在真实 Redis 3.2.100 上发现多字段 HSET 不兼容，已改为 Lua 内逐字段 HSET；真实集成测试验证初始化、重复片、摘要冲突、非连续成功序号、两键 TTL 续期、缺键拒绝重置与清理。默认跳过外部服务测试，显式运行方法见 README。

MySQL 是最终完成状态的依据。Redis 过期不删除视频资产，也不会自动删除对象存储分片。当前不承诺 Redis 数据意外丢失后的进度自动重建。

## 调用与失败边界

1. 初始化：先以 requestKey 的数据库唯一约束创建或找到会话，再核对文件参数；INITIALIZING 阶段创建 Redis 进度，成功后变为 UPLOADING。Redis 或对象存储初始化失败后仍保留同一请求键，重试可以继续初始化，不能盲目重建已有 UPLOADING 进度。
2. 每轮上传：前端查询进度并求缺片集合，受限并发执行本轮，等所有请求成功、失败或超时后再查下一轮。查询失败暂停；内容冲突或过期不自动重试。前端超时不代表后端停止。
3. 分片写入：后端在有界临时文件中计算摘要，使用 uploads/{uploadId}/parts/{index} 定位对象。对象成功后再写 Redis；对象已成功而 Redis 失败时，可以校验并复用原对象，再次记录进度。
4. 完成：先查 MySQL 资产，已存在就返回同一 assetId；否则检查齐片并进入 FINALIZING，禁止继续写入分片，按序合并到本地临时文件并校验，写入稳定的 videos/{uploadId}/original。
5. 资产入库：对象写入完成后，以短事务新增资产、标记会话 COMPLETED 并设置 cleanup_pending。对象 IO 不放在长数据库事务中。唯一约束只防止重复资产行，不能声称跨系统只执行一次。
6. 重试与找回：FINALIZING 失败可继续完成；最终对象已成功但数据库失败时，不报成功，重试核对稳定对象后复用。数据库已成功但 HTTP 响应丢失或 Redis 清理失败时，按 uploadId 读同一资产；不再依赖 Redis。
7. 读取：视频列表来自 MySQL，页面列出最近 100 条；资产详情和内容按 assetId 查询。内容接口核对 bucket、大小和保存摘要元数据，流式返回，并支持单段 Range。修改存储桶后与历史资产不匹配会明确拒绝读取，不会将旧键发往新桶。

## 主要接口

| 方法与路径 | 输入与输出 |
| --- | --- |
| GET /api/upload-settings | 返回开关和非敏感上传参数，默认关闭时也可读取 |
| POST /api/uploads | requestKey、文件名、大小、类型、指纹 → uploadId、状态和成功序号 |
| GET /api/uploads/{id} | 查询同一会话；完成后返回 assetId，过期返回 410 |
| PUT /api/uploads/{id}/parts/{index} | 原始二进制流及 X-Chunk-SHA256 → 成功 204 |
| POST /api/uploads/{id}/complete | 校验、合并与保存 → 资产记录；重复返回同一资产 |
| GET /api/videos | 最近 100 条资产记录 |
| GET /api/videos/{id} | 单条资产详情 |
| GET /api/videos/{id}/content | 流式读取；单段范围 206，无效范围 416；download=true 使用下载响应头 |

错误响应包含 code、message，区分输入问题、内容冲突、过期及外部服务不可用。外部适配器返回固定中文错误，不将 SDK 的端点或凭据错误原文返回页面。

## 临时数据清理

每 10 分钟进行一轮有限批量检查。过期会话在锁内重新核对最新期限和是否已有资产，只删除对应临时片及未入库最终对象；删除成功后标记 EXPIRED，失败保留可重试状态。已完成资产不在过期删除范围。

成功后的临时清理通过 cleanup_pending 持久标志重试；完成响应重试和调度均可继续处理。清理失败不撤销已保存资产。进程被直接终止时，本地合并临时文件可能遗留在忽略的 work/upload-tmp 中；当前未实现这类本地遗留文件的自动回收。

## 配置与验证边界

通用开发值、产品范围及验收见需求文档；私密连接信息只在外部 dev 文件。资源打包排除 application-dev.*，实际检查 JAR 不应包含私密配置或模板。

本轮已从用户指定的黑马点评 YAML 读取连接配置，仅写入本项目忽略文件。使用独立 vibeteaching 数据库，不修改原业务库。2026-11-16 接通 Redis 6379 与 MinIO 9000，将本机 dev 的上传开关开启；通用配置仍默认关闭。MinIO 管理页面为 9001，应用使用独立桶 vibeteaching-videos。沙箱内网络访问受限，真实服务与浏览器检查在获准的沙箱外执行。

真实联调使用本地生成的 13,080,719 字节 VP8 WebM，按默认 5 MiB 分为 3 片。浏览器上传一片后暂停、刷新并重选，复用同一 uploadId；人为注入一次分片 HTTP 503 后，下一轮先查成功集合且仅补失败片。真实 MinIO 写入、数据库保存、重复初始化与完成、Range 206/416、整文件下载摘要及实际播放均通过。

重启本次应用进程后，新浏览器上下文无 localStorage 续传记录，仍能按 uploadId/assetId 找回同一视频并下载、播放。独立验证进程确认同一 uploadId 只有一条资产、会话 COMPLETED、cleanup_pending=false、Redis 两键与三片临时对象均已清理，最终对象仍存在且大小/摘要与数据库一致。验收视频保留在本机资料库；测试输入、截图及证据在忽略的 work/ 中。

上述结果不包含 2 GiB 性能、Redis/MinIO 服务重启、跨实例协调或所有外部故障的真实环境验证。过期会话的自动清理仍以可控测试为证据，不能混同于本次真实完成后清理检查。

## 独立文字解析的链路

本轮将解析与总结分开：用户提交已保存的 `assetId`，不携带学习目标，也不调用总结模型、RAG 或 Agent。

```text
页面先 GET 找回任务
  → POST assetId + requestKey
  → MySQL 短事务保存任务和带待派发状态的 PREPARE 单元
  → 返回已持久受理的任务编号（不表示识别完成）
  → 定时扫描待派发的 parse_unit，发送到 RocketMQ
  → 消费者短事务领取阶段，数据库事务外执行媒体或识别 IO
  → 逐项保存结果、更新任务汇总状态
  → 页面轮询读取 MySQL 中的任务与文字
```

`PREPARE` 从 MinIO 有界下载原视频并核对大小与摘要，用 FFprobe 检查视频轨道和时长；FFmpeg 提取 16 kHz 单声道 PCM WAV 并分成约 60 秒片段，同时每 5 秒抽样一帧，最多 360 帧。图片不放大原画面，最长宽高限制在 2560×1440 范围内。当前原视频解析上限为 256 MiB、30 分钟，消费线程为 1；这些是开发值，不代表上限性能结论。

时长和预计抽帧数超限时，在识别前明确拒绝，不截掉后半段后报告成功。固定抽帧使用 `fps` 的 `round=up:eof_action=pass`，取采样网格时刻或此前最近的源帧，并保留末尾不足一个间隔的采样点；实际数量与预计不符时明确失败。音频通过重采样保留中间静音和初始音轨延迟，按 FFmpeg 分片 CSV 保存全局起止时间，片尾不超过视频时长。整体 `format.start_time` 超过 ±1 ms 时返回 `MEDIA_TIMELINE_UNSUPPORTED`；缺少整体起点时暂按 0，时长未知仍拒绝。整体非零起点与视频从 0 开始、仅音轨延迟是不同情况，后者已验证可保留前导静音。

预处理产出的 WAV 和 JPEG 先保存到 MinIO `parsing/{taskId}/{attemptToken}/...`，再用一个数据库事务保存其输入定位、时间范围、带待派发状态的处理单元，最后完成 PREPARE。输入保存成功但数据库事务失败时，不会启动对应识别；重试预处理可能生成另一组输入，已有孤立对象的自动回收尚未实现。

## ASR 与 OCR 的接入边界

生产装配采用 `DashScopeAsrClient` 调用阿里云百炼 `qwen-audio-3.1-asr-flash` 的同步非流式接口。每次只读取一个有界 WAV，编码为 `data:audio/wav;base64,...`，通过 HTTP JSON 提交音频内容，显式关闭 SSE，并读取 `output.text`。云端无需访问本机 MinIO，因此不需要为该协议公开 MinIO 或生成云端可达音频 URL。本轮未引入 Spring AI 或 LangChain4j；`AsrClient`、`OcrClient` 分离业务与厂商协议。

ASR HTTP 客户端关闭连接失败自动重放与重定向。鉴权或明确请求拒绝记失败；429 作为可重试限流；连接/响应中断、5xx、异常或不完整成功响应按 `UNCERTAIN` 处理，不自动再次提交。供应商已返回文字而数据库保存失败时，任务不会记成功；执行期限恢复后仍按 ASR 不确定结果处理，无法承诺保存丢失的文字或外部恰好调用一次。

OCR 使用本地 Tesseract，语言为 `chi_sim+eng`，返回纯文本。生产适配器以 stdin 输入图片，将工作目录设为显式可执行文件所在目录，存在相邻 `tessdata` 时使用该相对目录；stdout 与 stderr 分开读取，警告不混入识别文字。输入、输出与执行时间均有限制，进程超时强制终止后有界等待退出，再尝试清理当前调用的临时目录。正常空文字是成功空结果，无音轨时 ASR 明确 `SKIPPED`；工具执行或语言加载失败才记失败。ASR 单元的起止时间来自音频片段范围，未保存句子级对齐；OCR `startMs=endMs` 是抽样画面时间，不能推断文字在相邻截图间持续存在。

2026-11-17 的独立工具检查已实际运行 FFmpeg/FFprobe 9.0.2、Tesseract 5.5.3.20260724 和官方 `tessdata_fast` 中英文模型。`scripts/SetupMediaTools.ps1 -Offline` 校验固定下载摘要、便携提取并识别程序绘制截图；运行证据位于忽略的 `work/tools/media/evidence/`。未修改全局 PATH、注册表或系统服务，也未执行安装器。工具来源为 [FFmpeg 官网指向的 Gyan 构建](https://www.gyan.dev/ffmpeg/builds/)、[Tesseract 官方安装文档](https://github.com/tesseract-ocr/tessdoc/blob/main/Installation.md)指向的 [UB Mannheim](https://github.com/UB-Mannheim/tesseract/wiki)，归档解包器来自 [7-Zip 官方发行](https://www.7-zip.org/download.html)。

该 Windows Tesseract 构建的 `--list-langs --tessdata-dir` 使用含中文绝对路径时实际报错，工具脚本通过固定工作目录和 ASCII 相对参数规避；生产适配器通过上述 stdin、工作目录和相邻语言目录方式规避，已在中文仓库路径和真实完整链路验证。清晰课程截图中的中文四行识别正确，但 `taskId/assetId` 的 `I` 被识别成 `l`、`video_asset` 下划线丢失；另一张短中文标题样例也出现误识。模型加载或完整链路成功不能替代中文、代码识别精度验收。

显式开启的真实媒体集成检查共 6 项已通过，使用真实 FFmpeg/FFprobe/Tesseract 和内存对象存储，不调用付费 ASR：6 秒无音轨视频的 0/5 秒实际画面与尾帧、65 秒静音保留及 60 秒跨片、5 秒延迟音轨的前导静音、时长/抽帧数量超限拒绝、整体 7 秒非零起点拒绝、中英语言加载及英文样例识别。另有真实子进程超时后 PID 已退出、文件可立即删除的检查。具体执行方式和证据见 tasks.md。

## 解析标识、持久化与状态

| 标识或内容 | 保存位置与用途 |
| --- | --- |
| 解析 `requestKey` | 浏览器在提交前写 localStorage；`parse_request` 唯一绑定任务，同键换资产或解析配置拒绝 |
| `taskId`、`assetId`、解析配置指纹 | `parse_task`；`asset_id + profile_version` 唯一，不同请求键也可复用同一任务 |
| PREPARE / ASR / OCR 单元 | `parse_unit`，记录序号、代数、尝试数、输入对象、时间、文字、错误与执行期限 |
| 消息派发状态 | 同一行 `parse_unit` 的 `dispatch_attempts`、`next_dispatch_at`、`dispatched_at`；MQ 消息只携带任务、阶段、序号、代数，不携带媒体、文字或密钥 |
| 原视频、可恢复识别输入 | MinIO；已完成资产仍用原 `bucket + objectKey`，解析输入单独位于 `parsing/` 前缀 |
| 本地媒体工作文件 | 忽略的 `work/parse-tmp`，单次调用结束尝试清理；强制退出遗留的自动回收尚未实现 |

解析配置指纹包含服务地址、模型、音频分片、抽帧方案与 OCR 命令等影响输出的配置；不包含 API key，轮换密钥不会单独触发重新解析。工作者发现任务配置指纹与当前配置不同时拒绝静默使用新配置执行；历史读取优先当前配置任务，没有时可以返回该资产最近任务。

任务状态为 `QUEUED / RUNNING / SUCCEEDED / PARTIAL / FAILED`。单元状态另外包含 `SKIPPED / UNCERTAIN`：存在活动单元时任务保持等待或处理中；全部结束后，有失败且至少一项识别成功为 PARTIAL，没有识别成功为 FAILED；没有失败或不确定项为 SUCCEEDED。空文字仍是成功保存的识别结果。

## 处理单元派发、消费确认与恢复

1. 创建任务、请求绑定和带派发状态的 PREPARE 在同一短事务中提交。`ParseDispatchScheduler` 默认每轮间隔 2 秒，扫描最多 20 个状态为 QUEUED、`dispatched_at` 为空、执行与派发时间均到期的单元，发送到 RocketMQ。Broker 确认成功后按单元与当前代数记录 `dispatched_at`；失败则增加 `dispatch_attempts` 并推迟 `next_dispatch_at`。这三项均在 `parse_unit`，没有独立发件表。
2. 消费者按 `taskId + kind + unitIndex + generation` 条件领取 QUEUED 单元，写入执行令牌与期限；同消息重复到达、阶段已完成或代数过期时无法再次领取。长耗时 IO 不持有数据库锁；保存结果再次检查代数、RUNNING 状态和令牌，旧工作者不能覆盖恢复后的结果。
3. 单条消息在当前消费者线程中执行有界阶段，并完成数据库保存或失败交接后才返回成功；未能持久交接的运行异常交给 MQ 重投，数据库恢复扫描同时兜底。不会将工作丢进线程池后立即确认成功。
4. 运行期间心跳延长执行期限，默认租期 5 分钟。RUNNING 超时后，ASR 转为 UNCERTAIN 等用户确认；PREPARE/OCR 增加代数后重新排队，旧消息和旧执行令牌失效。
5. QUEUED 超过默认 10 分钟仍未领取时，清空同一行的 `dispatched_at` 并将 `next_dispatch_at` 设为当前时间，按同一代数补发；原排队消息继续有效，避免不断废弃老消息导致长队列饥饿。补发本身不执行识别，也不增加业务尝试数。
6. 可确认的暂时业务失败默认间隔 10 秒、最多尝试 3 次；手动重试仅重排 FAILED/UNCERTAIN 单元并保留成功项，存在 UNCERTAIN 时 API 要求 `confirmUncertain=true`。恢复与消息重投不构成跨系统原子事务或端到端 exactly-once。

发送回执只更新当前代数且仍为 QUEUED 的单元，旧代数回执不能覆盖重试后的派发状态。如果消费者已经领取或完成，晚到的发送回执可以忽略。发送成功但本地回执未保存时仍可能补发；领取条件拒绝重复执行。`dispatch_attempts` 记录发送失败次数，原有 `attempts` 记录实际领取执行次数，两者不能混用。重试增加代数时会重置发送字段；QUEUED 超时补发则保留代数。

## 从独立 Outbox 合并到处理单元（2026-11-19）

代码检索确认独立 Outbox 仅供解析使用，没有其他接口或业务订阅方。每个处理单元同时只存在一个有效执行代数，因此将当前代数的派发记录直接保存在单元行，减少独立表及关联查询。仍保留总任务和请求绑定表；“合表”指 `parse_unit` 与 `parse_outbox` 合并，不是将全部业务压成一张表。

Flyway 是数据库版本迁移工具；V3 是第三号迁移文件，不是 Flyway 软件版本。已执行的 V1/V2 保持原样，新增 `V3__merge_parse_dispatch_into_units.sql`。Flyway 在 `flyway_schema_history` 记录版本与校验值，启动时按序执行尚未应用的迁移；已应用的版本不应通过修改旧文件来重写。[版本迁移规则](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)

本次采用单实例停机升级，不是零停机双写迁移：

1. 停止旧应用，确认没有 RUNNING 单元；将独立数据库现有七张表的结构与数据快照保存到忽略的 `work/`，不复制连接凭据到文档。
2. 在 `parse_unit` 增加三个字段：发送失败次数 `dispatch_attempts`、下次可发送时间 `next_dispatch_at`、发送确认时间 `dispatched_at`，并增加派发扫描索引。
3. 按 `(task_id, kind, unit_index, generation)` 匹配旧发件记录，将 `attempts / next_attempt_at / sent_at` 回填到新字段。必须匹配代数，防止旧一轮已发送记录抑制新一轮待执行工作。
4. 没有当前代发件记录的单元，发送失败次数取 0、发送确认时间为空、下次发送时间取 `next_run_at`。只有 QUEUED 单元会被扫描，成功、失败、不确定、跳过或运行中的单元不会因默认值而重发。
5. 回填后删除 `parse_outbox`；保留任务、请求绑定、单元状态、原文、输入对象、执行次数、代数、租约与原有时间字段。旧代数的逐条发送历史不再保留，迁移前快照用于核对和必要的人工恢复。
6. 在应用恢复前，比较原有业务数据、当前代发送字段、旧表移除和 Flyway 版本。再启动新代码，检查历史结果读取与新的消息派发。具体实测范围见 tasks.md。

MySQL DDL 可能隐式提交，不能把整个 V3 当作失败后自动完整回滚的业务事务。中途失败应保持应用停机，检查已执行到哪一步，再据快照修复或前向补齐；Flyway `repair` 只处理迁移记录，不能替代数据恢复。本轮没有执行恢复演练，也不能直接将升级后的库交给依赖旧表的旧应用运行。[迁移事务边界](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)

面试口述示例：我先确认 Outbox 只有视频解析一个使用方，且每个处理单元只有一个有效代数，于是把消息派发状态合并到单元表，定时扫描待派发单元继续走 MQ。数据库通过新增 Flyway V3 升级，先停旧实例并保存快照，再按任务、阶段、序号和代数回填发送状态，最后移除旧表。升级不改识别原文，也不重新调用 ASR。验证重点是旧库升级后结果不丢、退避时间保留、旧代回执不能污染新代，以及重复消息不会重新执行成功单元。这次是停机迁移，不能包装成生产零停机迁移或端到端恰好执行一次。

## 本地 RocketMQ 运行方式

本轮已实际执行 RocketMQ 5.3.4 的 `scripts/SetupRocketMq.ps1` 和 `scripts/StartRocketMq.ps1`，下载、运行数据和日志保存在忽略的 `work/` 下。NameServer 9876、Broker 10911 及兼容端口 10909 仅监听 loopback。启动脚本创建或核对本地解析主题，停止脚本只操作 PID 与命令行均匹配当前项目的进程。

本地单 Broker 通过 SPI 加载 `LocalOnlyHaService`，不启动 HA 副本复制服务，10912 不监听；`LocalRocketMqBroker` 在启动前核对扩展是否生效。该方式仅用于本机单 Broker 实验，不能作为集群或主从复制配置使用；本机刷盘与持久任务派发状态也不能替代副本容灾。MQ 停机与恢复的具体实测结果由 tasks.md 记录。

## 解析接口与页面

| 方法与路径 | 输入与输出 |
| --- | --- |
| GET /api/parse-settings | 开关、配置可用性、中文原因及非敏感时长/抽帧参数，不返回密钥或厂商原始错误 |
| POST /api/videos/{assetId}/parse | `{requestKey}` → 任务及单元视图；等待或运行中为 202，已结束任务为 200；两者都不单独表示识别成功 |
| GET /api/videos/{assetId}/parse | 当前配置任务，或该资产最近历史；尚未开始为 `PARSE_NOT_STARTED`，资产不存在为 `ASSET_NOT_FOUND` |
| GET /api/parse-tasks/{taskId} | 任务汇总状态、每项识别文字、时间和失败说明 |
| POST /api/parse-tasks/{taskId}/retry | `{confirmUncertain}` → 仅重排允许重试的失败项；活动任务直接返回当前状态 |

`ready` 检查必需配置、指定模型/协议和本地可执行文件是否存在，不是服务健康或识别成功承诺；Broker 暂不可用时，配置齐备的任务仍可持久受理并等待重连。缺少工具或配置时页面禁用新建和重试，不做固定响应假解析。只要上传服务和数据库启用，解析开关关闭仍可 GET 历史。

页面只持久保存解析请求标识，不用浏览器进度作为事实。每次提交前先 GET，POST 响应丢失后再次核对；查询失败时暂停、保留最近结果，不按空任务重建。页面分别展示音频分片和截图时刻；存在 UNCERTAIN 时提供说明和明确的可能重复计费按钮。识别内容使用 `textContent`，不当作 HTML 执行。

## 私密解析配置与验证边界

真实配置仍仅放在被忽略且不跟踪的 `config/application-dev.yaml`，文档与示例只记录键名或占位值。新增键为 `app.parse.enabled`、`nameserver`、`ffmpeg-command`、`ffprobe-command`、`ocr-command`、`asr-url`、`asr-model`、`asr-api-key`；通用时长、大小、重试和目录参数在 `application.yaml`。启动继续显式选择 dev，不能将本轮真实凭据或完整 Data URI 写入日志、文档或提交。

当前自动化覆盖 H2 上正式迁移与真实仓储，默认测试中的 MQ/识别/存储故障使用可控替身；另有真实短音频 ASR、独立本地工具和上述真实媒体集成检查。

完整链路已使用 8.189 秒普通话本地 TTS 与课程截图组成的视频，经过真实 RocketMQ、MySQL、MinIO、本机媒体工具、百炼和浏览器，任务最终为 `SUCCEEDED`，保存 1 个 ASR 单元共 37 字、2 个 OCR 单元共 233 字。重启应用后，新浏览器仅发 GET 请求，仍读回同一 `taskId` 和已保存文字。这里分别验证了识别、持久化、页面展示与重启读取，没有据此证明长视频吞吐、所有外部故障或所有中文画面的识别质量。

详细命令、MQ 停机恢复结果、故障注入范围及未完成验收统一以 tasks.md 为准。

## 与参考 DOVideo 源码的差别

2026-11-16 至 2026-11-17 对照本地 DOVideo-AI 参考仓库的上传与分析代码。以下为静态源码对比，未启动该参考项目，也不把静态可见的失败窗口当成已复现故障。

| 项目 | 当前 VideoSight | 本机 DOVideo 参考代码 | 选择原因与代价 |
| --- | --- | --- | --- |
| 刷新重选文件 | 逐片 SHA-256 形成内容指纹，保留会话原分片大小 | 以用户、文件名、大小、lastModified 查本地续传记录 | 按内容核对原文件；开始前需要完整读取一遍文件 |
| 初始化幂等 | 浏览器先保存 requestKey，SQL 唯一键返回原会话 | 每次初始化生成 uploadId，拿到响应后再存浏览器 | 覆盖初始化响应丢失后的重试 |
| 上传编号找资产 | SQL 持久关联 uploadId 与唯一资产 | 媒体资产记录保存在 MySQL，但 uploadId 到 mediaId 的完成回执在 Redis 保留 25 小时 | 让该编号的长期召回不依赖临时回执；两边都能通过媒体资产列表读取已保存视频 |
| 补传 | 每轮重新查进度，等本轮结束后只补缺片 | 分片内部指数退避并带随机抖动重试 | 遵循用户确认的按轮流程，以服务端已确认集合裁决 |
| 进度写入 | Lua 同时校验片号、摘要、数量与两键期限 | MinIO 成功后 SADD，再分别续期元数据与片号集合 | 减少 Redis 内部分更新；仍没有跨 MySQL/MinIO/Redis 的原子事务 |
| 并发与归属 | 单用户、单实例，进程内锁串行化同一上传会话的关键操作 | 用户归属校验、Redisson 合并锁 | 先实现已确认范围；前端最多发 3 片不代表本后端同一会话三路并行 |
| 解析与总结 | 本轮独立解析并保存文字，未接总结 | 上传与分析为独立入口；分析经 RocketMQ 派发，先构建 ASR/OCR 上下文再运行 Agent | 先验证解析、保存与重新读取，再决定后续总结接口 |
| ASR 协议 | 固定 WAV 分片，经百炼 Data URI 同步接口提交；不依赖云端访问 MinIO | 约 60 秒 MP3 分片，通过可配置 HTTP multipart 上传并读取 text | 借鉴直接传文件内容，按实际选定厂商适配，不凭工具类名假设协议相同 |
| OCR | Tesseract；固定 5 秒采样，数量有界 | Tesseract；场景变化及 30 秒补帧，再做相似帧过滤 | 首版选择可解释的采样与上限，代价是重复画面和短暂画面遗漏 |
| 持久接单 | MySQL 任务、单元及派发状态同事务；可重复发送和恢复扫描 | Redis active 后直接投递 MQ，解析完成后保存 Context/Checkpoint | 补任务已保存但消息未发出的窗口，单元同时保存执行与派发状态 |
| 解析检查点 | 每个音频片/抽样画面独立保存和局部重试 | 整体 Context 完成后保存；后续 Agent 有 Checkpoint 恢复路径 | 减少已完成识别重跑，但不能消除外部成功而本地未保存的窗口 |
| 互斥与恢复 | 短事务领取、执行期限和代数；ASR 未知状态停等明确确认 | active 标记、内容级与分析级 Redisson 锁，按结果和 Checkpoint 复用 | 学习持久任务可靠性，不复制本轮尚不需要的用户/Agent锁层次 |

对应参考文件为 `client/src/chunkUpload.js` 及服务端的 `ChunkUploadService`、`AnalysisController`、`AnalysisDispatchService`、`VideoContextService`。当前参考源码使用 RocketMQ；其他分支或检出目录不一定相同。

## 保留的学习方向

T01 基线及上传已验证；2026-11-12 的字幕总结方案和 2026-11-13 的上传讨论保留为历史背景。本轮仅接入百炼语音转写与本地 OCR；总结模型、RAG、Agent 和多轮记忆仍需另定需求，不能将语音识别接通写成 Agent 已实现。
