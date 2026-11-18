-- 补齐异步状态机所需字段（设计文档第 4 节）
--
-- 【适用范围】仅存量库升级用。新库初始化请直接用 db/schema.sql —— 该文件已包含
-- 下面这四列与两个索引，对新库执行本脚本会报 Duplicate column name。
--
-- 本脚本非幂等：ADD COLUMN / ADD INDEX 重复执行会失败。若目标库已是新结构
-- （DESCRIBE t_pbccrc_report 能看到 status 列），直接跳过，不要执行。
--
-- 执行前先备份：mysqldump ... pbccrc > pbccrc_before_migration.sql
-- 2026-11-17 修正：字段统一追加到表末尾，不使用 AFTER 锚点列
--                  （锚点列若不存在会导致整条 ALTER 回滚；字段顺序对按列名取值的代码无影响）。
ALTER TABLE t_pbccrc_report
  ADD COLUMN status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '解析状态:PENDING/PARSING/SUCCESS/FAILED',
  ADD COLUMN retry_count     INT          NOT NULL DEFAULT 0          COMMENT '解析重试次数',
  ADD COLUMN heartbeat_time  DATETIME     DEFAULT NULL                COMMENT '解析心跳时间,识别卡死',
  ADD COLUMN content_hash    VARCHAR(64)  DEFAULT NULL                COMMENT '提交幂等键：报告正文全文MD5（见 migration_2.1_idempotency.sql）',
  ADD INDEX IDX_PBCCRC_STATUS (status),
  ADD INDEX IDX_PBCCRC_CUSTOMER_NAME_ID (customer_name, customer_id_card);

-- 迁移前已存在的报告视为解析完成，避免被补偿任务误扫
UPDATE t_pbccrc_report SET status = 'SUCCESS' WHERE status = 'PENDING';
