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

### 3.1 建库与升级

- **新库**：直接执行 `db/schema.sql`。它是完整基线，已包含状态机四列（`status` / `retry_count` /
  `heartbeat_time` / `content_hash`）与两个关键索引（`IDX_PBCCRC_STATUS`、
  `IDX_PBCCRC_CUSTOMER_NAME_ID`）。
- **存量库**：执行 `db/migration.sql`（**非幂等**，四列追加到表末尾并补建上述索引），执行前先备份；
  若已能看到 `status` 列则跳过，重复执行会报 Duplicate column。

两份脚本此前并不一致：`schema.sql` 缺这四列，而 `ReportMapper` 的 17 条语句全都在用 `status`，
按旧基线建库后首个查询即 `Unknown column 'status'`；缺少 `IDX_PBCCRC_CUSTOMER_NAME_ID` 时
`findRecentForUpdate` 的 `SELECT ... FOR UPDATE` 会退化为全表扫描加锁，多笔提交相互阻塞并与解析侧
更新死锁。该漂移现由 `SchemaContractTest` 固化为构建期断言（见 §3.2），不再依赖人眼比对。

### 3.2 数据访问层与 SQL 组织

SQL 统一放在 `src/main/resources/mapper/*.xml`（由 `mybatis.mapper-locations` 装配），Mapper 接口
只保留方法签名与 javadoc，不用 `@Select` / `@Update` 注解承载 SQL。

| 文件 | 语句 | 说明 |
|---|---|---|
| `ReportMapper.xml` | 17 | 主表读写与状态机 CAS；`markSuccess` 34 列逐行对齐；后台两条查询共用 `AdminWhere` 片段 |
| `DetailQueryMapper.xml` | 12 | 明细读取，均按 `report_id` 过滤 |
| `ReportContentMapper.xml` | 3 | 报告原文 |
| `ReportQueryLogMapper.xml` | 2 | 提交流水（`create_time` 是 date 列，用 `CURDATE()`） |
| `RuleMapper.xml` / `CustomerHistoryMapper.xml` | 各 1 | 规则加载 / 审计留痕 |
| `DetailMapper`（Provider） | 2 | **例外**，见下 |

`DetailMapper` 保留 `DetailSqlProvider` 而不搬 XML，是刻意选择：12 张明细表共用一个写入器，
表名在运行期决定。XML 里只能写 `FROM ${table}`，而 `${}` 是字符串替换、有注入面，现在的白名单
校验（`DetailSqlProvider.table()`）恰好长在 SQL 层；搬进 XML 等于把唯一防线挪给调用方。

反射也只剩这一处：`SqlColumnUtil` 按实体字段生成插入列清单。它的失败模式是「响的」（列缺失 →
插入报错 → 事务回滚 → 报告 FAILED + 告警），所以保留；但它是**隐式约定**，因此由
`SchemaContractTest` 在构建期把实体字段与 `db/schema.sql` 的列逐一比对，隐式约定变可验证断言。

修改 Mapper 时的三条约束：

1. 加接口方法必须同时加同名 statement —— `MapperXmlLoadTest` 遍历接口方法断言 statement 存在，
   「XML id 打错」「新增方法忘了补 SQL」会在构建期而不是首个请求时报错。
2. 状态机语句的 `WHERE ... AND status = ?` 必须写在 XML 里、保持可读，不要在 Java 侧拼装：
   状态流转的正确性依赖对这几处 CAS 的人工复核。
3. 明细查询用 `SELECT *` 是有意为之（实体与列一一对应，多余列被忽略）；代价是列改名会静默映射
   为 null，若将来给明细表加同义列，应改为显式列清单。

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

### 4.4 规则加载期的回溯风险校验（`RegexSafetyChecker`）

规则是人工维护的，一条写错的正则就能把解析线程钉死；而 `java.util.regex` 既不支持匹配超时，也不响应线程中断（见 §9.6），跑飞之后没有安全的止损手段。因此把检查放在加载期：`RuleCache.build` 在 `Pattern.compile` 之前先做结构扫描。

| 检查项 | 判定 | 级别 | 触发示例 |
|---|---|---|---|
| `NESTED_REPEAT` | 被重复修饰的组内部含无界量词，且外层为无上界或重复 ≥3 次 | 阻断加载 | `(a+)+`、`(\d*)*`、`(.*a){8}` |
| `EMPTY_REPEAT` | 被重复修饰的组可匹配空串，且外层为无上界或重复 ≥10 次 | 阻断加载 | `(a?)+`、`(a\|)*` |
| `MULTI_WILDCARD` | 同一模式内 ≥2 处 `.*`／`.+` | 仅告警 | `发放的.*（(.*)）` |

阈值的依据是实测（统计 `charAt` 次数，输入 29 个字符）：`(.*a){2}` 1.7 万次、`{4}` 64 万次、`{6}` 997 万次、`{8}` 7891 万次。有界重复到 3 次就足以在单条短文本上烧掉数百毫秒，所以嵌套重复的阻断线定在 3，而不是 10。

需要如实说明两点：

1. HotSpot 对部分经典形态（如 `(a+)+`）有内建优化，同一输入只产生千余次读取，实测并不爆炸。检测仍按结构判定——优化是实现细节，规则移植、JDK 升级或模式稍改都可能让它失效，宁可拦住等价的坏写法。
2. 校验只做结构启发式，不做完整语法分析（语法错误仍由 `Pattern.compile` 报）；字符类与转义内的元字符会被跳过，零宽断言内部的分组照常检查。

现网 55 条规则全部通过该校验，其中 `CURRENCY_TYPE`、`CREDIT_TYPE` 命中 `MULTI_WILDCARD` 告警（各 2 处 `.*`），不影响加载。

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
3. **容错**：`ParsePipeline.safe()` 对每块 try/catch，单块失败只记 warning 日志，不中断整份报告（部分成功）。warning **不写入 `error_msg`**——该字段只承载失败原因，见 5.4。
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
打一条 `WARN` 并计入解析告警（**只进日志**），**判成功不打断**，不直接
判失败——避免因正文损坏把可解析的报告打成 FAILED 后反复重试。该字段不参与提交幂等。

### 5.3 报告结构校验（判失败的唯一依据）

「解析成功」的判据是**结构存在**，不是**有数据**。真实征信报告即使完全没有任何信贷／公共／查询
记录，版式上仍会保留三个基础区块标题：

| 基础区块 | 样例中的形态 |
|---|---|
| 信贷记录 | `<strong class="h1">信贷记录</strong>` |
| 公共记录 | `<td class="h1">公共记录</td>` |
| 查询记录 | `<td class="h1">查询记录</td>` |

`BlockSplitter.split` 扫 `.h1` 把命中的区块名记入 `Parts.sections`，`ParseServiceImpl` 在落库前
用 `ensureReportStructure` 校验三块齐全（`BlockSplitter.REQUIRED_SECTIONS`）。因此：

| 输入 | 结果 | 说明 |
|---|---|---|
| 结构齐全 + 有明细 | SUCCESS | 正常路径 |
| 结构齐全 + **零明细** | **SUCCESS** | 空报告是合法的：解析出空数据，主表仍回填报告编号等头部字段 |
| 缺任一基础区块 | **FAILED** | `error_msg` 写明缺失项与已识别项，进重试／死信流程 |

只认 `.h1` 标题本身，不认正文里出现的同名字样——否则任何含「查询记录」四字的 HTML 都会被误判。
这条规则拦的是「上游传错文件 / HTML 被截断」：修复前这类输入会产出 `status=SUCCESS`、明细全空、
看起来完全正常的报告，把问题彻底掩盖掉。

### 5.4 `error_msg` 的语义：只说明失败原因

`error_msg` 只有一个用途——回答「这份报告为什么失败」。它由失败链路（`markFailed`）写入，
成功时恒为 `NULL`（`markSuccess` 显式回填 null 以清除历史值）。

解析过程中的**降级**——某块标题没认出来、某条明细套规则失败、公共记录出现未知类别、正文哈希
对不上——属于**部分成功**：报告本身仍然可用，明细能落多少落多少。这类信息只进日志
（`ParsePipeline` 汇总打一条 `WARN`），**不写 `error_msg`**。原因有两条：

- 后台展示会把 `error_msg` 当成错误提示，正常报告凭空多出一条「错误」；
- 按「`error_msg` 非空即异常」配置的监控会持续误报。

由此得到一个直接的业务结论：**任何一类明细为空都是正常结果**。报告本身可能就没有信用卡、
没有公共记录、没有查询记录，后台照常展示原始 HTML 报告即可，不需要为空明细造任何告警或提示。
真正需要警惕的只有「结构缺失」（5.3），那会被判 FAILED。

空报告连信息概要表都不会有（页面上没有账户数据时该表不渲染），因此
`CreditSummaryParser` 找不到概要表时只记 `debug` 日志、不产生告警，主表 15 个汇总字段保持 NULL。


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
- 事务内二次校验：在提交事务内对 `t_pbccrc_report` 按「姓名 + 证件 + 近 7 天 `create_time`」做 `SELECT ... FOR UPDATE`（`ReportMapper.findRecentForUpdate`），命中即拒绝；走 `IDX_PBCCRC_CUSTOMER_NAME_ID` 索引（新库由 `db/schema.sql`、存量库由 `db/migration.sql` 建）。无需对 querylog 做结构变更（`db/migration_2.1_idempotency.sql` 仅做列注释更正）。
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
- 数据库初始化顺序：新库执行 `db/schema.sql`（完整基线）；存量库执行 `db/migration.sql`。详见 §3.1。
- 测试：`mvn test`（61 个用例）。分层见 `REVIEW.md` 第三节：E2E 真实报告全流水线（`FullReportParseTest`，7 例）、
  真实 `submit()` 链路幂等（`SubmitServiceImplIdempotencyTest`，7 例）、解析锁语义（`ParseServiceImplLockTest`，2 例）、
  公共记录未识别篇章留痕（`PublicRecordParserTest`，2 例）、正则回溯风险校验（`RegexSafetyCheckerTest`，11 例）、
  H2 状态机 CAS（`ReportStateMachineTest`，5 例）、Mapper 与 XML 绑定（`MapperXmlLoadTest`，2 例）、
  实体/基线列一致性（`SchemaContractTest`，3 例）、55 条正则全编译与抽取（`RegexEngineTest`，5 例）等。
- 完整验证入口：`bash scripts/verify.sh`（`mvn clean test` + `mvn javadoc:javadoc`，打印证据块与结论）。
- 管理端前端（`admin-web`）：鉴权开启后前端从 `localStorage.pbccrc_token` 读取 token 并统一加
  `X-Auth-Token` 请求头（`src/api/index.js` 的请求拦截器），无需在每个调用点单独处理。

## 9. 待复核 / 待补项

1. **6 处正则修复 + type6/7 归类**：待 Alan 复核确认。
2. **公共记录异常样本**：当前仅有「系统中没有」类样本，其余先原文留痕，样本补齐后细化字段。
3. **存量库迁移尚未执行**：`db/migration.sql` 需在备份后执行。在它执行前，因缺 `status` 等四列，
   `ReportMapper` 的全部语句都会报 `Unknown column`；因缺 `IDX_PBCCRC_CUSTOMER_NAME_ID`，
   `findRecentForUpdate` 会退化为全表加锁。**这版代码上线前必须先跑该脚本**，或按 §3.1 用新基线建库。
4. **公共记录分类的兜底分支**：`PublicRecordParser.classify` 是 if/else-if 链，命中不到任何关键词的
   段落目前**逐条打 `WARN` 日志**（含 `reportId`、记录序号与 200 字原文摘要，并追加一条「共 N 条未识别」
   汇总行），原文同时完整保留在 `common_record_detail`，不丢数据、不再静默。后续与第 5 项（客户概要表
   规则流程）一起处理：未识别段落改为落独立的原文留痕表，按样本反推缺失类别与规则。
   > 注意：日志只用于回收样本，不能作为数据兜底——排障时不要以日志代替原文表。
5. **`t_pbccrc_customer_credit` 下线**：本服务已不读不写，是否 DROP 待业务确认；与第 4 项的规则流程
   一并设计（若恢复该表写入，需同时定义未知类别留痕表）。
6. **正则执行仍不可中断（已做加载期预防，未做运行期隔离）**：卡点在 `RegexEngine.extract`
   （`RegexEngine.java:39-57`）的 `matcher.find()`；`ParseServiceImpl` 的看门狗（`:147-169`）能按
   `ParseDeadline` 判出卡死并标记失败、发告警，但 `finally`（`:212-215`）只停看门狗，**从不中断解析线程**，
   且 `java.util.regex` 本身不响应中断。现在的防线是 §4.4 的加载期拒绝，运行期仍有残余风险。
   若后续需要彻底止损，只有两条路：换 RE2/J（线性时间，需先扫现有规则是否用到反向引用/环视），
   或把正则执行放到独立进程、超时 `kill`。
7. **真实中间件验证不在 `mvn test` 内**：`SubmitRealRedisSmoke`、`ReportMapperRealSqlSmoke`、
   `OtherMappersRealSqlSmoke` 三个类名不以 `Test` 结尾，CI 不会执行，改动 SQL 后需手动跑：
   ```bash
   PBCCRC_DB_PWD=xxx PBCCRC_REDIS_PWD=xxx \
   PBCCRC_DB_URL='jdbc:mysql://127.0.0.1:3306/pbccrc_verify?...' \
     mvn -Dtest=SubmitRealRedisSmoke,ReportMapperRealSqlSmoke,OtherMappersRealSqlSmoke test
   ```
   建议指向独立的验证库（如 `pbccrc_verify`，用 `db/schema.sql` 建库），不要指向开发库。
   这是当前唯一的验证盲区来源：`mvn test` 全绿并不代表方言正确。
   > 教训：`SubmitRealRedisSmoke` 曾在 SQL 迁 XML 后静默失效整整一轮，正因为没人跑它。
8. ~~**`SUCCESS` 状态下 `error_msg` 可能非空（待决）**~~ **已决（§5.4）**：告警只进日志，不再写
   `error_msg`；`CreditSummaryParser` 找不到概要表时降级为 `debug`。成功报告 `error_msg` 恒为 NULL。
9. **真实报告样例不入版本库**：`src/test/resources/sample_report.html`、`report_all.html` 与
   `db/sample_report.html` 是人行征信报告原文，含个人金融信息，已从索引移除并加入 `.gitignore`，
   只在本地保留。统一由 `SampleReport` 读取，文件缺失时用 `Assumptions` 把用例标为 skipped
   （本机有样例：69 例全跑；样例缺失：12 例 skipped，构建仍 SUCCESS）。
   代价：`FullReportParseTest`、`ParsePipelineTest` 的真实报告用例在 CI 上覆盖为零。若需要 CI 也验证，
   应另造一份**不含真实个人信息**的合成报告并入版本库，而不是把真实报告提交上来。

## 10. 变更记录

### 2026-11-18 评审整改

对应评审报告 `docs/CODE-REVIEW-2026-11-18.md`。基线由「E2E 2 例失败」转为全绿（44 例全通过），
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
| 加固 | 规则加载期做回溯风险结构校验：嵌套重复／可空重复拒绝加载，多处通配只告警（阈值按实测 reads 定） | `RegexSafetyChecker`、`RuleCache.build` |
| 观测 | 公共记录未识别类别逐条 `WARN`（reportId + 序号 + 200 字摘要）并汇总条数，原文仍整体保留在 `common_record_detail`；不做静默丢弃，也不写入 `error_msg` | `PublicRecordParser` |
| 清理 | 删除未被引用的 `CustomerCredit` 实体 | `model/entity` |

### 2026-11-18 SQL 治理

起因：审查 DAO 层时发现「反射生成 SQL」实际只存在于一处，真正的风险是 `schema.sql` 与
`migration.sql` 各写一份、状态机语句与表结构可能各改各的。

| 类别 | 改动 | 位置 |
|---|---|---|
| 基线 | `schema.sql` 补齐状态机四列与 `IDX_PBCCRC_STATUS`、`IDX_PBCCRC_CUSTOMER_NAME_ID`，并在文件头写明「新库用它、存量库用 migration.sql」 | `db/schema.sql`、`db/migration.sql` |
| 基线 | `report_querylog.content_hash` 列注释改正（不参与幂等）；说明其唯一键因该列恒为 NULL 实际不生效 | `db/schema.sql` |
| 重构 | 36 条注解 SQL 迁入 `resources/mapper/*.xml`；`markSuccess` 由 1.4 KB 单行改为 34 行逐列；后台 count/list 共用 `AdminWhere` 片段 | `mapper/`、各 Mapper 接口 |
| 例外 | `DetailMapper` 保留 Provider（动态表名 + 白名单校验在 SQL 层），不搬 XML | `DetailMapper`、`DetailSqlProvider` |
| 修复 | `markFailed` 增加 `AND status='PARSING'`：看门狗在 `markSuccess` 提交后迟到判超时时不再把成功报告改回 FAILED | `ReportMapper.xml` |
| 测试 | `MapperXmlLoadTest` 遍历接口方法断言 statement 存在；`SchemaContractTest` 比对实体字段/XML 赋值列与 `db/schema.sql` | `src/test/.../dao` |
| 测试 | `ReportStateMachineTest` 新增「迟到判失败不覆盖 SUCCESS」用例（5 例） | 同上 |

未采纳 MyBatis-Plus：本项目 38 条语句、无多表 join，MP 省下的样板有限；而 `updateById` 默认
`NOT_NULL` 策略会让 `markSuccess` 清空 `error_msg` 的语义静默失效，批量插入需自定义
`SqlInjector` 才能保住现有的「500 行一条多行 INSERT」（评审 3.7），收益不抵迁移风险。

### 2026-11-18 真实环境自测

起因：SQL 迁入 XML 后复盘发现，全部 62 个用例跑在 H2 的 MySQL 兼容模式上，`SubmitRealRedisSmoke`
虽然是唯一连真实中间件的用例，却不随 `mvn test` 执行——**生产口径的 SQL 此前零验证**。
本轮连本机 MySQL 8.0.27 与 Redis 实测，暴露了一个真实回归并补上三层验证。

| 类别 | 内容 |
|---|---|
| 发现的缺陷 | SQL 迁 XML 后 `SubmitRealRedisSmoke` 失效：它手工 `new Configuration()` 并只 `addMapper(接口)`，不读 XML，首个 `submit` 即抛 `Invalid bound statement: ReportMapper.findRecentForUpdate`。**测试失效但无人察觉**，因为它不在 `mvn test` 里 |
| 根因 | 手工构建的 MyBatis `Configuration` 与 `application.yml` 口径不一致：`map-underscore-to-camel-case` 默认 false、`jdbc-type-for-null` 默认 OTHER、`call-setters-on-nulls` 默认 false。默认值跑出来的结论不代表线上行为 |
| 修复 | 新增 `MapperXml.productionLike(env, mappers...)` 作为唯一口径来源（三项显式对齐 yml 并装载 XML），三个 smoke 统一改用它 |
| 新增验证 | `ReportMapperRealSqlSmoke`：主表 17 条语句逐条真库执行，含 `DATE_SUB(NOW(), INTERVAL ? MINUTE)`、`LIMIT #{offset}, #{pageSize}`、`markSuccess` 34 个 SET 项的逐字段校验（列序错位即失败）、状态机 CAS 守卫（SUCCESS 不被迟到失败覆盖） |
| 新增验证 | `OtherMappersRealSqlSmoke`：其余 5 个 Mapper 19 条语句，含 `INSERT IGNORE` + `CURDATE()`（流水表 `create_time` 是 date 而非 datetime）、`RuleMapper` 两表 JOIN、12 条明细查询的表名列名 |
| 新增验证 | `MapperLocationWiringTest`：直接读 `application.yml` 的 `mybatis.mapper-locations` 原值，用 Spring 同一个 `PathMatchingResourcePatternResolver` 匹配，断言 6 个 XML 一个不落且接口方法全部有 statement。此前该配置写错不报编译错，只在上线首个请求炸 |
| 结论 | `db/schema.sql` 在 MySQL 8.0.27 上直接建出 19 张表，四列与两索引齐全；36 条语句全部执行通过；真实 Redisson 锁 TTL 实测 604799961ms（≈7 天），同身份不同正文被真实 `trySet` 拒绝 |
| 顺带证实 | 流水表唯一键 `(customer_name, customer_id_card, content_hash)` 因 `content_hash` 恒为 NULL 确实不生效——连插两次得两行，已写成 `OtherMappersRealSqlSmoke` 的断言，杜绝「有唯一键所以能去重」的误判 |

三个 smoke 类名不以 `Test` 结尾，仍不并入 `mvn test`（避免 CI 依赖外部中间件），运行方式见
§9 第 7 条。

### 2026-11-18 报告结构校验

起因：真实环境验证时提交了一段非征信 HTML，结果 `status=SUCCESS`、明细全空、`error_msg` 只有一句
「未找到信息概要表」——**一份看起来完全正常的空报告**，上游传错文件这件事被彻底掩盖。
定下的规则：判失败看结构，不看条数。

| 类别 | 改动 | 位置 |
|---|---|---|
| 判定 | `Parts` 新增 `sections`，扫 `.h1` 识别「信贷记录／公共记录／查询记录」三块；只认标题本身，不认正文里出现的同名字样 | `BlockSplitter` |
| 判定 | `ParseContext` 承接 `sections`，由 `ParsePipeline` 灌入 | `ParseContext`、`ParsePipeline` |
| 行为 | 落库前 `ensureReportStructure` 校验三块齐全，缺任一抛异常走 `markFailed`，`error_msg` 写明缺失项与已识别项 | `ParseServiceImpl` |
| 语义 | 明确「零明细」不是失败：结构齐全的空报告照常 SUCCESS，主表仍回填报告编号等头部字段 | §5.3 |
| 测试 | `ReportStructureTest` 5 例：两份真实样例三块齐全、非报告 HTML 零区块、正文同名字样不算结构、结构齐全但零明细仍识别、截断报告缺项准确 | `src/test/.../parser` |

真实环境（MySQL 8.0.27 + Redis + 55 条规则）三场景实测：

| 输入 | 状态 | 明细 |
|---|---|---|
| `report_all.html`（41272 字符） | SUCCESS | 12 卡 + 5 贷 + 69 查询，共 86 |
| 仅三个 `.h1` 标题、无任何数据 | SUCCESS | 全 0，`report_no` 与姓名正常解析 |
| `<p>这不是一份征信报告</p>` | FAILED | `无法识别为征信报告：缺少基础区块 [信贷记录, 公共记录, 查询记录]，已识别区块 []` |

`verify.sh` 67 例全通过（原 62 + 新增 5），javadoc 0 error。

### 2026-11-18 明细允许为空，`error_msg` 只承载失败原因

起因：结构校验上线后，一份结构齐全、零明细的合法空报告得到 `status=SUCCESS` +
`error_msg="未找到信息概要表，跳过汇总回填"`——后台展示把降级提示当成了错误。
定下的口径：**各类明细为空都是正常业务结果，后台直接展示原始 HTML 报告，不应出现任何错误**。

| 类别 | 改动 | 位置 |
|---|---|---|
| 语义 | `ParsePipeline` 不再把区块告警拼进 `report.errorMsg`，只汇总打一条 `WARN` | `ParsePipeline` |
| 语义 | 找不到信息概要表（空报告的常态）由 `warn` 降级为 `debug`，不再产生告警 | `CreditSummaryParser` |
| 语义 | 正文哈希不一致仍打 `WARN`，但只计数告警、不写 `error_msg` | `ParseServiceImpl` |
| 契约 | 成功报告 `error_msg` 恒为 `NULL`；失败原因仍由 `markFailed` 写入 | §5.4、`ReportMapper.xml` |
| 测试 | `ParsePipelineDiagnosticsTest` 2 例：空报告零告警且 `error_msg` 为 null；标题未识别的降级仍被采集但不污染 `error_msg` | `src/test/.../parser` |



