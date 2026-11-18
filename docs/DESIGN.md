# 人行个人征信报告 HTML 解析入库系统 — 设计文档

> 工程：`credit-report`　版本：1.0.0-SNAPSHOT　Java 21 / Spring Boot 3.3.5 / MyBatis 3.0.3

## 1. 目标与范围

把中国人民银行个人征信报告的 HTML 文件解析成结构化数据入库。解析规则**配置驱动**：正则和值处理方式存数据库，改规则不重新编译。系统提供报告提交、异步解析、状态跟踪、失败补偿，以及后台按「身份证 + 姓名」查询的管理接口。

## 2. 总体架构

```
                ┌──────────── POST /reports ────────────┐
                │                                       │
Client ────────>│  ReportController                      │
                │   ├─ SubmitService   存原文,返回id      │
                │   └─ ParseService   异步解析(虚拟线程)  │
                └────────────────────────────────────────┘
                                  │
                    ┌─────────────▼──────────────┐
                    │  ParsePipeline              │
                    │  报告头/信息概要/信贷明细/    │
                    │  公共记录/查询记录/说明       │
                    └─────────────┬──────────────┘
                                  │  BlockSplitter(Jsoup) 切块
                                  │  RegexEngine + RuleCache 抽字段
                                  ▼
                    ┌─────────────────────────────┐
                    │  ParseResultWriter           │
                    │  主表汇总 + 各明细表          │
                    └─────────────┬──────────────┘
                                  ▼
                          MySQL  pbccrc 库
```

- Web 层：`ReportController`
- Service 层：`service` 包下为接口（`SubmitService` / `ParseService` / `ReportQueryService` / `ParseResultWriter` / `AlertNotifier`），实现统一放 `service.impl` 包并以 `Impl` 后缀命名（`SubmitServiceImpl` / `ParseServiceImpl` / `ReportQueryServiceImpl` / `ParseResultWriterImpl` / `AlertNotifierImpl`）
- Job 层：`job` 包下 `CompensationJob`（定时补偿任务，非对外暴露服务，不建接口）
- 解析层：`ParsePipeline` + 六块子解析器 + `BlockSplitter` + `HtmlUtil`
- 引擎层：`RegexEngine` / `FieldRule` / `RuleCache` / `ValueConverter` / `ValueType`
- DAO 层：MyBatis Mapper（主表、原文、规则、查询流水、明细通用写入）

## 3. 数据库模型

表前缀 `t_pbccrc_`，完整 DDL 见 `db/schema.sql`。

| 表 | 用途 |
|---|---|
| `t_pbccrc_report` | 主表：客户标识、汇总字段、解析状态、重试/心跳、幂等键（`content_hash` 为正文 MD5，解析前校验） |
| `t_pbccrc_report_content` | 报告 HTML 原文 |
| `t_pbccrc_report_model` | 解析模板（按 type 分组） |
| `t_pbccrc_report_model_value` | 规则明细：字段名 + 正则 + `value_index` |
| `t_pbccrc_report_querylog` | 提交流水（仅记录，不承担去重）；去重靠 t_pbccrc_report 近 7 天窗口 + Redis 锁 |
| `t_pbccrc_credit_card_record` | 信用卡明细 |
| `t_pbccrc_loan_record` | 贷款明细 |
| `t_pbccrc_query_record` | 查询记录明细（机构/个人） |
| `t_pbccrc_guarantee` / `asset_disposal` / `compensate` | 担保 / 资产处置 / 代偿 |
| `t_pbccrc_court_decision` / `ccivil_decision` / `admin_punishment` | 民事判决 / 行政处罚 |
| `t_pbccrc_tax_arrears` / `telecom_owe` | 欠税 / 电信欠费 |
| `t_pbccrc_customer_history` | 客户历史 |

> `t_pbccrc_customer_credit`（信息概要表）为历史遗留表，**本服务不读不写**：概要数据落在
> `t_pbccrc_report` 的 15 个汇总字段上。原先对应的 `CustomerCredit` 实体已删除，避免误以为
> 写入后能查到数据。表本身未从库中清理，是否下线待业务确认。

### 3.1 需补齐的主表字段（`db/migration.sql`）

运行服务前须先备份再执行。真实库建表时缺少以下字段：

```sql
ALTER TABLE t_pbccrc_report
  ADD COLUMN status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态:PENDING/PARSING/SUCCESS/FAILED' AFTER app_no,
  ADD COLUMN retry_count     INT          NOT NULL DEFAULT 0          COMMENT '解析重试次数' AFTER status,
  ADD COLUMN heartbeat_time  DATETIME     DEFAULT NULL                COMMENT '解析心跳时间,识别卡死' AFTER retry_count,
  ADD COLUMN content_hash    VARCHAR(64)  DEFAULT NULL                COMMENT '报告正文全文MD5，仅作内容完整性校验（不参与提交幂等）' AFTER heartbeat_time,
  ADD INDEX IDX_PBCCRC_STATUS (status);
UPDATE t_pbccrc_report SET status = 'SUCCESS' WHERE status = 'PENDING';
```

## 4. 配置驱动解析引擎

### 4.1 规则来源与缓存

- `t_pbccrc_report_model_value` 每条规则 = 字段名 `name` + 正则 `value` + `value_index`。
- `RuleCache` 启动时 `@PostConstruct` 加载全部规则，按 `type` 分组缓存为 `Map<String, List<FieldRule>>`。
- **任一正则非法立即抛 `BizException` 阻断启动**，不静默吞掉（早期发现配置错误）。

### 4.2 value_index 语义

格式 `组序号:第几次匹配`（如 `1:2` = 第 1 捕获组、第 2 次匹配）。缺省 `1:1`。由 `FieldRule` 解析，`RegexEngine.extract` 按 `matchIndex` 次 `find()` 后取 `groupIndex`。

### 4.3 值类型转换

`ValueType.of(fieldName)` 按字段名约定推断类型，新增字段遵守命名即可，无需改代码：

| 类型 | 命名约定 |
|---|---|
| `DATE` | 以 `_DATE`/`DATE` 结尾，或含 `_TIME` |
| `MONEY` | 含 `MONEY`/`AMOUNT`/`BALANCE`/`LIMIT`，或以 `_SUM` 结尾 |
| `FLAG` | `BAD`/`SETTLEMENT`/`ACTIVE`/`CLOSE_ACCOUNT` |
| `INT` | 以 `_MONTH`/`_NUM` 结尾，或含 `COUNT`/`_TOTAL`/`NINETY`/`SIXTY` |
| `STRING` | 其它（默认） |

`ValueConverter.convert` 负责金额（去千分位）、日期、标志位（是/否）等归一化。

## 5. 解析流程

1. **切块**：`BlockSplitter.split(html)` 用 Jsoup 把报告切成六部分——报告头、信息概要表、信贷明细（多个 `ol.olstyle`，每个含 `<li>` 自然语言描述）、公共记录、查询记录（`tr` 表格）、说明。
   - **公共记录的定位是本环节最容易出错的地方**：真实报告的整份正文被一个最外层 `<table>` 包着，它的 `text()` 里必然出现「公共记录」四个字。因此定位规则是：先按 `.h1` 标题文本（「公共记录」）用 `closest("table")` 取得最近的 table；标题缺失时退化为「在包含『公共记录』的候选 table 中取文本最短者」（外层包装表的长度是真正区块的几十倍）；两者都没有才退回「返回『系统中没有』原文」。**不得**改用「文档顺序第一个命中的 table」——那会在报告真存在公共记录时把整份报告当成公共记录，导致信贷明细被二次分类、担保记录重复。
   - 命中后，紧随其后的兄弟 table 中凡含「电信」的一并收进公共记录文本，供电信欠费解析。
2. **六块解析**：`ParsePipeline` 依次调用：
   - `ReportHeadParser` — 报告头（姓名、证件号等）
   - `CreditSummaryParser` — 信息概要汇总表
   - `CreditDetailParser` — 信贷明细，按 `ParseType` 分流（见 5.1）
   - `PublicRecordParser` — 公共记录
   - `QueryRecordParser` — 查询记录
   - `DescriptionParser` — 说明
3. **容错**：`ParsePipeline.safe()` 对每块 try/catch，单块失败只记 warning 到 `error_msg`，不中断整份报告（部分成功）。
4. **入库**：`ParseResultWriter` 回填主表汇总字段 + 各明细表。所有明细（信用卡/贷款/担保/资产处置/代偿/公共记录各类）都必须保留 `content` 原文，便于规则变更后回溯与对账。

### 5.1 解析类型分流（`ParseType`）

信贷明细区按 `span.h1` 标题关键词分流，**标题白名单之外的块会被整块丢弃**，因此新增报告模板时先确认标题能命中：

| 标题关键词 | 归入 | `ParseType` code |
|---|---|---|
| 信用卡 | `t_pbccrc_credit_card_record` | 1 |
| 购房贷款 / 其他贷款 | `t_pbccrc_loan_record` | 2 |
| 资产处置 | `t_pbccrc_asset_disposal` | 3 |
| 保证人代偿 | `t_pbccrc_compensate` | 4 |
| 为他人担保 | `t_pbccrc_guarantee` | 5 |
| 担保信用卡 / 担保贷款（公共记录区内） | `t_pbccrc_guarantee` | 6 / 7 |

type 6/7 按关键词归入担保表，并区分「担保信用卡 / 担保贷款」。

### 5.2 正文完整性校验（`content_hash`）

提交时把正文 MD5 写入 `t_pbccrc_report.content_hash`。解析前重算一次并与登记值比对，不一致时
记一条 warning 到 `error_msg`（「报告正文与登记的内容哈希不一致」），**判成功但留痕**，不直接
判失败——避免因正文损坏把可解析的报告打成 FAILED 后反复重试。该字段不参与提交幂等。


## 6. 提交幂等与状态机

### 6.1 提交幂等

- 业务规则：**7 天滚动窗口内，同一「姓名 + 身份证」组合不允许重复提交**；窗口过后同一客户可再次提交。去重维度是身份证号+姓名，与报告正文无关。
- Redis（必需依赖，无开关）：`pbccrc:submit:lock:{md5(证件|姓名)}` 原子锁，TTL = `submit-lock-ttl-days`（默认 7 天），`trySet` 失败即拒。原子锁天然保证窗口、过期可重提与并发安全；Redis 不可用则提交直接失败，不再退化。
- **锁的值是一次性令牌（UUID），不是幂等键**：同身份两次请求令牌不同，释放时用
  `compareAndSet(令牌, null)` 原子比较删除，确认「删的是自己那把锁」。早期直接 `delete()`，
  TTL 过期后同身份新请求已重新占位时，旧请求的回滚会误删新锁，幂等被击穿。
- **释放必须覆盖两种失败**：
  1. 方法体内异常 → catch 后立即释放（用户可马上重试）；
  2. **事务提交阶段失败**（死锁、连接中断等，异常发生在 `submit()` 返回之后，catch 兜不住）→
     注册 `TransactionSynchronization.afterCompletion`，仅在 `STATUS_ROLLED_BACK` 时释放。
     不处理这一条，锁会白占 7 天而主表没有记录，客户被永久拒之门外。
- 事务内二次校验：在提交事务内对 `t_pbccrc_report` 按「姓名 + 证件 + 近 7 天 `create_time`」做 `SELECT ... FOR UPDATE`（`ReportMapper.findRecentForUpdate`），命中即拒绝；走 `IDX_PBCCRC_CUSTOMER_NAME_ID` 索引（由 `db/migration.sql` 建）。无需对 querylog 做结构变更（`db/migration_2.1_idempotency.sql` 仅做列注释更正）。
- `t_pbccrc_report_querylog` 恢复为纯提交流水，`insertIgnore` 冲突只忽略，不参与幂等判定。
- 先存原文返回 `reportId`，再异步解析。

### 6.2 状态机（`ReportStatus`）

```
PENDING ──抢占──> PARSING ──成功──> SUCCESS
                    │
                    ├─失败─> FAILED (可重试)
                    └─卡死─> 补偿扫描重投
```

- CAS 抢占：`PENDING -> PARSING`
- 进度时间戳心跳（评审 2.3）：解析流水线每推进一个阶段才刷新 `heartbeat_time`（经进度回调），**不再**定时无条件刷新；看门狗虚拟线程仅在「距上次阶段推进超过 `parse-timeout-minutes`（默认 20）」时判卡死并标记失败，使真正卡住的解析（正则回溯、连接池耗尽等）可被发现，而非只能发现进程死亡。
- `markSuccess` 带 `status='PARSING'` 的 CAS 条件（评审 3.6），返回值必须校验；超时判失败后迟到的结果无法覆盖，事务回滚避免明细挂在非解析中报告上。
- 失败重试：`retry_count < maxRetry(5)`；手动重试仅允许 `FAILED`，拒绝重置 `PARSING` 以防并发重复解析（评审 3.3）。
- 卡死/可重试补偿：`CompensationJob` 定时扫描（`dead-letter-scan-cron`，默认每 5 分钟）
- 死信：超过重试上限进死信，`AlertNotifier` 预警；重投走同一入口，天然幂等。告警按
  `pbccrc:deadletter:alerted:{reportId}`（TTL 24h）去重，同一份未解决的死信每天提醒一次，
  不会每 5 分钟刷屏。
- 明细写入按 500 条分批，避免撞 MySQL `max_allowed_packet`（评审 3.7）。

### 6.2.1 解析互斥锁

- 锁键 `pbccrc:parse:{reportId}`，**非阻塞获取**：`tryLock(0, lease, MINUTES)`。拿不到说明已有
  节点在解析（或上一轮卡死的线程仍持锁），直接跳过，交给补偿任务下一轮重投。
  早期用阻塞的 `lock(30min)`，补偿重投时新线程会原地等旧线程最长 30 分钟，白占一个虚拟线程。
- 租期 `lease = parseTimeoutMinutes × 2`（默认 40 分钟，不低于 10 分钟）。看门狗判超时只会
  `markFailed`，**不会中断跑飞的线程**（正则灾难性回溯在 JVM 层面不可中断），所以租期必须活过
  「超时判定」这个时间点，才能让补偿的重投在 `tryLock` 处被挡住，避免同一份报告两个线程同时
  删改明细。超过租期仍未结束属残余风险，靠 `markSuccess` 的 CAS 条件兜底（第二次落库会回滚）。

异步执行用 JDK 21 虚拟线程（`AsyncConfig`）。

### 6.3 管理端最小鉴权（评审 2.5）

- `pbccrc.security.enabled=true` + `pbccrc.security.token` 开启固定 token 鉴权（`X-Auth-Token`，支持 `Bearer ` 前缀）；默认关闭以兼容本地/测试。
- `AuthFilter` 对 `/api/pbccrc/**` 全部接口校验，未授权返回 401。
- 校验通过后从可信请求头（`X-Operator`）读取操作人写入 `OperatorContext`，业务层优先据此留痕，避免客户端在 body 自报工号；请求结束即清理防线程串用。
- 说明：这是最小可用方案，生产应叠加内网白名单 / 网关注入身份并妥善保管 token。

## 7. HTTP 接口

基础路径 `/api/pbccrc`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/reports` | 提交报告：存原文返回 `reportId`，异步解析。body=`SubmitRequest` |
| GET | `/reports/{id}` | 查询解析状态 |
| POST | `/reports/{id}/retry` | 手动重试（仅 `FAILED` 状态，`PARSING` 拒绝以防并发） |
| GET | `/dead-letters` | 死信列表 |
| **POST** | **`/admin/reports/query`** | **后台管理：按身份证 + 姓名分页查询（新需求），有 operator 时写查询流水** |
| GET | `/admin/reports/{id}/detail` | 单份报告全量明细 |

管理端查询 body 示例：

```json
{
  "customerIdCard": "110...",
  "customerName": "张三",
  "pageNum": 1,
  "pageSize": 10,
  "operator": "admin01"
}
```

## 8. 运行与配置

- 数据库：`127.0.0.1:3306/pbccrc`，账号 `root`，口令运行时通过环境变量 `PBCCRC_DB_PWD` 提供（不落盘）。
- 本机 MySQL 需 `--no-defaults` 绕开 `my.ini` 客户端段误配的 `server-id=1`。
- Redis 为必需依赖，无开关：地址 `pbccrc.redis.address`（默认 `redis://127.0.0.1:6379`）、逻辑库 `pbccrc.redis.database`（默认 1）；口令通过 `PBCCRC_REDIS_PWD` 提供（不落盘）。Redis 不可用则应用启动或相关操作直接失败。
- 构建：`mvn package`；jar：`target/credit-report-1.0.0-SNAPSHOT.jar`
- 测试：`mvn test`（42 个用例）。分层见 `REVIEW.md` 第三节：E2E 真实报告全流水线（`FullReportParseTest`，7 例）、
  真实 `submit()` 链路幂等（`SubmitServiceImplIdempotencyTest`，7 例）、解析锁语义（`ParseServiceImplLockTest`，2 例）、
  H2 状态机 CAS（`ReportStateMachineTest`，4 例）、55 条正则全编译与抽取（`RegexEngineTest`，5 例）等。
- 完整验证入口：`bash scripts/verify.sh`（`mvn clean test` + `mvn javadoc:javadoc`，打印证据块与结论）。
- 管理端前端（`admin-web`）：鉴权开启后前端从 `localStorage.pbccrc_token` 读取 token 并统一加
  `X-Auth-Token` 请求头（`src/api/index.js` 的请求拦截器），无需在每个调用点单独处理。

## 9. 待复核 / 待补项

1. **6 处正则修复 + type6/7 归类**：待 Alan 复核确认。
2. **公共记录异常样本**：当前仅有「系统中没有」类样本，其余先原文留痕，样本补齐后细化字段。
3. **migration.sql**：待 Alan 备份后在执行（主表状态字段）。执行前 `findRecentForUpdate` 没有复合索引。
4. **公共记录分类的兜底分支**：`PublicRecordParser.classify` 是 if/else-if 链，命中不到任何关键词的
   段落会被静默丢弃（不告警）。样本补齐后应改为「未知类别进原文留痕表」。
5. **`t_pbccrc_customer_credit` 下线**：本服务已不读不写，是否 DROP 待业务确认。

## 10. 变更记录

### 2026-11-18 评审整改

对应评审报告 `docs/CODE-REVIEW-2026-11-18.md`。基线由「E2E 2 例失败」转为全绿（42 例全通过），
**未修改任何既有断言**，失败是靠修代码转绿的。

| 类别 | 改动 | 位置 |
|---|---|---|
| P0 | 公共记录改为按 `.h1` 标题定位 + 最小候选兜底，不再把整份报告当公共记录（担保 9→3 条、电信欠费 4→5 条） | `BlockSplitter.extractPublicText` |
| P0 | 信贷块新增「资产处置」「保证人代偿」两类标题分流，真实记录不再被整块丢弃 | `CreditDetailParser` |
| P0 | 公共记录分类回填 `content` 原文；资产处置/代偿条数回填移出早退分支 | `PublicRecordParser` |
| P1 | 提交幂等锁值改为一次性令牌 + `compareAndSet` 释放；新增事务回滚回调释放锁 | `SubmitServiceImpl` |
| P1 | 解析互斥锁改非阻塞 `tryLock(0, 2×超时)`，补偿重投不再原地等待 | `ParseServiceImpl` |
| P1 | 死信告警 24 小时去重 | `CompensationJob` |
| P1 | 解析前用 `content_hash` 校验正文完整性，不一致只留痕 | `ParseServiceImpl` |
| P2 | `BeanFillUtil` 捕获类型不匹配异常，单字段失败不再击穿整块 | `BeanFillUtil` |
| P2 | 正则组序号越界改为「按未命中处理」并告警，不再退化成整段匹配 | `RegexEngine` |
| P2 | 标志位改为显式映射表，未登记字段告警 | `ValueConverter` |
| P2 | token 常量时间比较 | `AuthFilter` |
| P2 | 测试 DDL 与 `db/schema.sql` 对齐（datetime / int / text） | `ReportStateMachineTest` |
| P2 | 管理端统一注入 `X-Auth-Token` | `admin-web/src/api/index.js` |
| 清理 | 删除未被引用的 `CustomerCredit` 实体 | `model/entity` |


