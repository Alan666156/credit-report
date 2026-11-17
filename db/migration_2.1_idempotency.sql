-- 提交幂等口径更正（取代早先误改成「正文全文哈希去重」的版本）
--
-- 业务规则（Alan 明确要求）：7 天滚动窗口内，同一「姓名 + 身份证」组合不允许
-- 重复提交；窗口过后同一客户可再次提交。去重维度是身份（姓名+证件），与报告
-- 正文无关。报告正文 MD5（content_hash）只作内容完整性校验字段，不参与幂等。
--
-- 实现分层：
-- 1) Redis（必需依赖）：pbccrc:submit:lock:{md5(证件|姓名)} 原子锁，TTL = 7 天，天然
--    实现「7 天窗口 + 过期可重提 + 并发安全」。
-- 2) 事务内二次校验：提交事务内对 t_pbccrc_report 按姓名+证件+近 7 天
--    create_time 做 SELECT ... FOR UPDATE 行锁判断，命中即拒绝。
-- 两层都不依赖对 querylog 表做结构变更，因此本脚本【无需执行任何 DDL】即可工作。

-- 下面仅把 content_hash 的列注释改回真实语义，属可选、无害的注释维护：
-- 不执行也不影响幂等逻辑。执行前照例先备份。
ALTER TABLE t_pbccrc_report
  MODIFY COLUMN content_hash VARCHAR(64) DEFAULT NULL
  COMMENT '报告正文全文MD5，仅作内容完整性校验（不参与提交幂等）';

-- 说明：
-- - 提交幂等查询走 t_pbccrc_report 上已有的 IDX_PBCCRC_CUSTOMER_NAME_ID
--   (customer_name, customer_id_card) 索引，配合 create_time 过滤窗口，无需新增索引。
-- - t_pbccrc_report_querylog 恢复为纯提交流水表，不再承担幂等去重；其原有
--   (customer_name, customer_id_card) 唯一键保持不变即可，insertIgnore 冲突只忽略。
