# 验证契约（Verification Contract）

> 本文件是**评审规则的唯一来源**。规则放在改动之外（对应原文：Code Review 从 base/default
> 分支读取 REVIEW.md，而不是从被评审的 PR 读取），任何提交都**不得修改本文件来让自己「通过」**。
> 依据：Addy Osmani《How the Claude Code team thinks about verification》(claude.dev, 2026-11-16)。

## 一、硬不变量（禁止削弱）

1. **提交幂等**：7 天滚动窗口内，同一「姓名 + 身份证」不允许重复提交，**与报告正文无关**。
   - 幂等键 = `md5(证件号|姓名)`；`content_hash`（正文 MD5）只做完整性校验，**禁止混入幂等键**。
   - 窗口起点 = `当前时间 - submitLockTtlDays 天`（默认 7）。窗口外或不同身份放行。
2. **Redis 为必需依赖**：`RedissonClient` 无条件注入，幂等锁/解析互斥锁/补偿主节点锁必用，
   不得出现「可选 / 默认关闭 / 不可用退化 / 本地兜底」分支。
3. 以上两条的测试断言写死在测试代码里，**不得为了让测试变绿而改写断言或放宽规则**。

## 二、「完成」的定义（证据口径）

汇报一次改动为「完成」时，必须同时给出：

- 按哪份规则：本文件 REVIEW.md；
- 测了哪个样本：如真实报告 `src/test/resources/report_all.html`；
- 断言口径：断言写在哪、验证什么不变量；
- 对应哪次改动/提交（有 git 时写提交号）。

汇报分三栏写清，**不得混进一句「已完成、全绿」**：

- 已修（改动了什么、证据）；
- 范围外（本次明确不做的）；
- 遗留问题（已知未解决、需他人提供样本或执行的）。

代码一旦再被改动（**包括被其他进程并改**），此前的「通过」结论立即标 **STALE**，必须重跑验证，
不得沿用旧结论。

## 三、测试纪律

- 禁止为跑绿而：改断言、放宽规则、加 sleep、反复重跑到绿、跳过测试、修改检查条件。
  测试从头绿到尾，恰恰可能没测到该修的地方。
- Flaky 测试不许「重试了事」：找不到根因就删除该测试并写明原因；不许训练出「重跑而非修根因」的习惯。
- 测试优先级：
  1. **E2E（端到端）**：真实报告走完整解析流程（`FullReportParseTest`），最难糊弄；
     提交幂等的真实链路走 `SubmitServiceImpl.submit()`（`SubmitServiceImplIdempotencyTest`，
     Redis 假实现带真实 trySet 成败语义，确保「拒重复」不是靠 stub 成 true 假绿）；
     `SubmitRealRedisSmoke` 连**真实 Redis(db=1)+真实 MySQL** 驱动同一个 `submit()`，验证假实现
     覆盖不到的 Redisson 真实 TTL/原子语义与 MyBatis 真实 SQL+表结构；用独立身份、跑完即清理、
     密码走环境变量（`PBCCRC_DB_PWD`/`PBCCRC_REDIS_PWD`）不硬编码，需手动跑、不并入默认 `mvn test`：
     `PBCCRC_DB_PWD=… PBCCRC_REDIS_PWD=… mvn -Dtest=SubmitRealRedisSmoke test`。
  2. **属性测试**：把不变量写成规则，用生成器产出大量输入去证伪（`SubmitIdempotencyPropertyTest`，
     输入不来自写代码的人，避免共享盲区）；
  3. **回归护栏**：锁住历史上出错的具体点（`SubmitServiceKeyTest`——幂等键曾错用正文哈希）；
  4. **差分测试**：Redis 锁与 DB 二次校验在窗口判定上必须一致（`SubmitServiceImplIdempotencyTest`
     的「锁过期但主表仍在窗口内」用例）；迁移/重写时旧新系统同输入对比，允许明确列出的预期差异。
- 同一检查连续 **3 轮**修复仍失败 → 停止，转人工（Alan），附样本与尝试记录。

## 四、规则变更流程

任何对本文件硬不变量的修改，必须**同时**满足：

1. 该变更诉求在 ≥2 次独立改动/评审中反复出现（不是一次性的临时便利）；
2. 经 3 次独立尝试仍无法被驳回（反驳不倒）；
3. Alan 明确批准。

否则不得修改。任何「为了通过测试」而改动本文件或测试断言的行为，一律视为违规。

## 五、可复用验证入口

运行 `bash scripts/verify.sh`（或按其中命令手动执行）：

```
mvn -q clean test          # 单元 + E2E + 属性 + 回归护栏
mvn -q javadoc:javadoc     # 文档 0 error
```

脚本打印证据块：规则文件、样本、各测试类用例数/错误/失败、javadoc 退出码、提交号、结论。
「PASS」只表示这些检查本次全过；代码再变即失效（STALE）。
