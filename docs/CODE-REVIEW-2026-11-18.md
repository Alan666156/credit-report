# credit-report 代码评审

> 评审对象：`credit-report` 工作区当前状态（HEAD `efb915d` + 未提交改动）
> 评审日期：2026-11-18
> 评审依据：`REVIEW.md`（硬不变量：提交幂等 / Redis 必需依赖 / 测试纪律）

## 0. 先说结论

**当前基线不绿。** 按 `scripts/verify.sh` 的口径，现在会输出 `[verdict] FAIL`：

| 检查 | 结果 |
|---|---|
| `mvn clean test` | 35 用例，**2 失败**（`FullReportParseTest`） |
| `mvn javadoc:javadoc` | 0 error / 0 warning |

两个失败不是「断言写错」，而是**代码在「报告确实有公共记录」这一场景下的真实缺陷**被新样本撞出来了。
按 `REVIEW.md` 第三节「禁止为跑绿改断言」，**不能调断言或改样本**，要修 `BlockSplitter.extractPublicText`。

未提交的改动里有 `src/test/resources/report_all.html`（删掉了一段「系统中没有您最近5年内的…」公共记录块）。
正是这次改动把下面的 P0-1 暴露出来——换句话说，这个样本改动是有价值的，别把它回退掉。

---

## 1. P0 — 影响数据正确性，必须修

### P0-1 公共记录取到了整份报告，导致公共记录表被信贷数据污染

**位置**：`parser/BlockSplitter.java:227-257`（`extractPublicText`）

```java
for (Element table : doc.select("table")) {
    String tx = table.text();
    if (HtmlUtil.contains(tx, "公共记录") && !HtmlUtil.contains(tx, "系统中没有")) {
        real = table;   // 取文档顺序第一个命中的
        break;
    }
}
```

真实报告的整份正文被一个最外层 `<table width="980" ...>` 包着，它的 `text()` 自然包含「公共记录」。
只要最外层 table 里没有出现「系统中没有」这句话，这个循环第一个命中的就是**整份报告**。

**实测证据**（`report_all.html`，共 19 个 table）：

| table | 文本长度 | 含「公共记录」 | 含「系统中没有」 |
|---|---|---|---|
| #1（最外层） | 8053（全文 8060） | true | **false** ← 被选中 |
| #10（真正的公共记录块） | 70 | true | false |

**后果**（同一个根因，三处症状）：

1. 担保明细 **9 条，应为 3 条**。多出的 6 条来自 `PublicRecordParser` 对信贷正文的二次分类，且 `content` 为 `null`。
2. 电信欠费 **4 条，应为 5 条**（分类时被前面的 `else if` 抢走）。
3. `common_record_description` / `common_record_detail` 存进去的是整份报告正文（几 KB），不是公共记录段。

**为什么之前是绿的**：被删掉的那段「系统中没有…」正好也在最外层 table 里，让 `hasNone` 为 `true`，
于是外层 table 被跳过、命中了内层的真块。这是**靠数据巧合过关**，不是逻辑正确。

**建议修法**（三选一，从稳到简）：

- 先按 `.h1` 标题文本定位「公共记录」块，再取该标题所在 table 之后、且不含「这部分包含/查询记录/为他人担保」的第一个 table——与 `DescriptionParser` 找「说明」的写法一致；
- 或收集**所有**候选 table，取 `text().length()` 最小的那个（真块 70 vs 全文 8053）；
- 或改判据：候选 table 的文本不得包含「报告编号」「查询时间」这类报告头特征词。

改完必须重跑 E2E；两个断言应当自然变绿，而不是被改绿。

### P0-2 信贷块标题白名单漏两类，资产处置与保证人代偿的真实记录进不了库

**位置**：`parser/CreditDetailParser.java:55-66`

只认 4 个标题关键词：`为他人担保` / `信用卡` / `购房贷款` / `其他贷款`。
而 `report_all.html` 里还有两个 `ol.olstyle` 块：**「资产处置信息」（1 条）**、**「保证人代偿信息」（1 条）**。

现在这两个块只打一条 warning 就被丢掉：

```
信贷块标题未识别，已跳过: 资产处置信息，条目数=1
信贷块标题未识别，已跳过: 保证人代偿信息，条目数=1
```

**后果**：`t_pbccrc_asset_disposal` / `t_pbccrc_compensate` 里看不到真实记录。
表里能查到的那 1 条 asset / 1 条 compensate，是 P0-1 的副作用——从被撑大的公共记录文本里误分类出来的。
两个 bug 叠加，正好互相掩盖。

`ParseType` 里 `ASSET_DISPOSAL("3")` / `COMPENSATE("4")` 有定义、规则也在库里，只是没接上。
**建议**：把标题分支补成 `资产处置`→`ASSET_DISPOSAL`、`保证人代偿`→`COMPENSATE`，走与「为他人担保」相同的 `parseGuarantees` 式路径。

### P0-3 公共记录分类不回填原文，`content` 为 null

**位置**：`parser/PublicRecordParser.java:80-125`（`classify`）

`资产处置` / `代偿` / `担保` 三个分支都只做了 `base(...)` + `fill(...)`，**没有 `e.setContent(text)`**；
另外 5 个分支（行政处罚/民事判决/强制执行/欠税/电信）都有 `setContent`。

这与类注释「其余先原文留痕，样本补齐后细化字段」直接矛盾。实测 9 条担保里 6 条 `content=null`，
后续补规则、做对账、排查误分类时都没有原文可回溯。

---

## 2. P1 — 并发与一致性风险

### P1-1 事务提交失败时，Redis 幂等锁不会释放 → 客户被误锁 7 天

**位置**：`service/impl/SubmitServiceImpl.java:79`（`@Transactional`）、`92`（`trySet`）、`125-129`（catch 释放）

```java
try {
    ...
    return report.getId();
} catch (RuntimeException e) {
    bucket.delete();   // 只兜住方法体里的异常
    throw e;
}
```

`@Transactional` 的提交发生在**方法返回之后**（代理层）。如果提交阶段炸了（死锁、连接中断、唯一键冲突、`max_allowed_packet`），
异常根本进不到这个 catch：

- Redis 锁已占位，留在 7 天；
- 主表没有记录；
- 客户端拿到 500。

结果：该客户 7 天内无法再提交，且系统里没有任何报告可查——**幂等锁把一次失败永久固化了**。

**建议**：注册 `TransactionSynchronization.afterCompletion(STATUS_ROLLBACK)` 里释放；
或者把「占 Redis → 开事务」拆开（先占位，事务失败再按 P1-2 的方式安全释放）。

### P1-2 `bucket.delete()` 会删掉别人的锁

同上的 `127` 行。TTL 到期后同身份的新请求可能已经重新占位，此时旧请求的回滚把 `delete()` 无差别执行，
就把**新请求的锁删了**，幂等被击穿。

**建议**：比较 value 再删（Lua / `RBucket.compareAndSet`），或直接改用 `RLock` 的持有者语义。

### P1-3 解析互斥锁无等待上限；看门狗判超时并不会让跑飞的线程停下来

**位置**：`service/impl/ParseServiceImpl.java:82`（`lock.lock(30, TimeUnit.MINUTES)`）、`116-138`（看门狗）

两件事：

1. `lock.lock(...)` 是**阻塞式**获取。补偿任务把卡死报告 `resetToPending` 后重投，新线程会一直等旧线程的锁（最长 30 分钟），
   期间占着一个虚拟线程 + 事务/连接；而报告状态已经是 PENDING，补偿下一轮又会再投一次。
   应改为 `tryLock(0, leaseTime)`，拿不到就跳过，交给下一轮补偿。
2. 看门狗命中超时后只做 `markFailed` + 告警，**不会中断解析线程**。DESIGN 2.3 说要能发现「正则灾难性回溯」，
   但现在只是让看门狗知道了，线程继续烧 CPU。30 分钟后锁到期，报告已是 FAILED，补偿可以 `claim` 成功，
   于是同一份报告有两个线程在写——`markSuccess` 的 CAS 能挡住第二次落库（这点做对了），但明细表的删/插会重复执行。

**建议**：给 `Matcher.find()` 加可控上限（预编译规则时校验回溯风险、限制单条 `li` 文本长度），
或把解析放进可 `Future.cancel(true)` 的边界里；至少要让「超时」等于「停下」。

### P1-4 死信告警每 5 分钟重复一次

**位置**：`job/CompensationJob.java:89-93`

每轮扫描都把全部死信重新 `notify` 一遍。现在是日志实现看不出问题，接钉钉/邮件后就是刷屏。
**建议**：加已告警标记（表字段 `alerted_at`，或 Redis set + TTL）。

---

## 3. P2 — 健壮性与可维护性

| # | 位置 | 问题 |
|---|---|---|
| 1 | `util/BeanFillUtil.java:41-47` | 只 `catch (IllegalAccessException)`。`field.set` 类型不匹配抛的是 `IllegalArgumentException`，会冒到 `ParsePipeline.safe`，**整块**记 warning 丢数据——与注释「单字段失败不影响整份报告」不符。应 catch `RuntimeException` 并带上字段名告警。 |
| 2 | `engine/RegexEngine.java:44-45` | `groupCount() >= groupIndex ? group(groupIndex) : group()`：组号越界时静默退化成「整段匹配」，会把整条 li 文本当成字段值入库。配置写错不会有任何提示。 |
| 3 | `engine/ValueConverter.java:116-126` | flag 是「0=是」的反向语义（`BAD`/`SETTLEMENT`/`CLOSE_ACCOUNT` 命中回 `"0"`），且兜底 `return "1"`。目前与 `rules.tsv` 的 `(已变成呆账)/(已销户)/(未激活)` 和 SQL 注释是对的，但任何**新增** FLAG 字段都会默认拿 `"1"`。建议改成显式映射表，未识别即告警。 |
| 4 | `content_hash` / `t_pbccrc_customer_credit` | `content_hash` **只写不读**（grep 只有 insert 和赋值），DESIGN 说的「完整性校验」没落地。`t_pbccrc_customer_credit` + `CustomerCredit` 实体是死代码，但 DESIGN 第 3 节仍把它列为在用表。 |
| 5 | `dao/ReportMapper.java:195-231` | `LIKE CONCAT('%',?, '%')` 前后都带通配，走不了索引；`createTimeStart/End` 用 `String` 传给 `datetime` 列，格式错了不报错只是查不到。 |
| 6 | `admin-web/src/api/index.js` | 请求不带 `X-Auth-Token`。一旦 `pbccrc.security.enabled=true`，前端所有接口 401。另外 `admin-web/dist/` 已提交进仓库（构建产物不该进版本库）。 |
| 7 | 测试 DDL | `ReportStateMachineTest` 里 `query_time VARCHAR(100)`、`asset_total DECIMAL(18,2)`、`error_msg VARCHAR(1000)`，生产是 `datetime`/`int`/`text`。H2 上过的类型，生产不一定成立。 |
| 8 | `controller/ReportController.java:138` | 直接注入 `ReportMapper` 做状态重置，绕过 service 层。CAS 本身是对的（只重置 FAILED），只是分层不一致。 |
| 9 | `config/AuthFilter.java:79,114` | token 用 `String.equals`（非常量时间，理论上的时序侧信道）；`shouldNotFilter` 只按 `/api/pbccrc/` **带斜杠**前缀，精确路径 `/api/pbccrc` 不过滤（目前无该映射，属隐患）。 |
| 10 | `dao/ReportQueryLogMapper` | `insertIgnore` 用 `CURDATE()`（只到天），同一天同客户多次提交流水会互相撞唯一键被 IGNORE，流水不全。虽然流水不承担去重，但既然是留痕，粒度到秒更合理。 |

---

## 4. 做得对的地方（别在重构时改坏）

- **状态机 CAS**：`claim` / `resetToPending` / `resetFailedToPending` / `markSuccess` 的条件都对，
  `resetToPending` 接受 PENDING 这个坑（`ReportMapper.java:82-93` 的注释）写得清楚，`ReportStateMachineTest` 也钉住了。
- **`markSuccess` 校验返回值 + 抛异常回滚**：超时判失败后迟到的结果无法覆盖，明细不会挂在非 PARSING 的报告上。
- **动态表名白名单**（`DetailSqlProvider.ALLOWED_TABLES`）+ 全 `#{}` 占位：这层通用明细写入没有注入面。
- **幂等测试分层**：`SubmitServiceImplIdempotencyTest` 用带真实成败语义的 Redis 假实现驱动真实 `submit()`，
  确实能抓住「trySet 返回值被忽略」这类假绿，比单纯 stub `true` 强得多。
- **Javadoc 全量覆盖**，`javadoc:javadoc` 零 error。

---

## 5. 建议的修复顺序

1. **P0-1**（`extractPublicText`）→ 重跑 E2E，两个失败应自然转绿。这是一切的前提，其他修复都要在这之上验证。
2. **P0-2 / P0-3**（信贷块标题补两类 + 分类回填 content）→ 补断言：`assetDisposals.size()==1`、`compensates.size()==1`、所有明细 `content != null`。
3. **P1-1 / P1-2**（锁释放）→ 补一个「提交事务回滚后锁应释放」的测试。
4. **P1-3**（tryLock + 真超时）→ 补一个「重复投递不会产生两个并发解析」的测试。
5. P2 按需。

每条修完都按 `REVIEW.md` 第二、三节的口径汇报（规则 / 样本 / 断言 / 提交号），别混一句「已完成、全绿」。

---

## 6. 本次评审的边界

- **我改了什么**：没有改任何代码。只新增了本文件。
- **我跑了什么**：`mvn -o clean test`（35 用例 / 2 失败）、`mvn -o javadoc:javadoc`（0 error）、
  一个临时诊断程序（读完即删，未留在仓库）用于确认公共记录取块逻辑。
- **范围外**：`SubmitRealRedisSmoke`（需真实 Redis + MySQL 与口令）未执行；`db/migration.sql` 是否已在目标库执行过未确认；
  业务侧「7 天窗口」是否为最终规则不以本次评审为准。
- **遗留**：需要样本才能定的字段（公共记录其余类别细化、type6/7 归类）仍按 DESIGN 第 9 节挂起；
  P0-1 修完后建议把 `report_all.html` 的两个版本（有/无「系统中没有」段）都作为回归样本，防止再靠数据巧合过关。
