
-- ============================================================================
-- 人行征信报告解析服务 —— 数据库完整基线（mysqldump 导出结构）
--
-- 【新库初始化只用本文件】本文件已包含全部演进：
--   · 异步状态机四列（status / retry_count / heartbeat_time / content_hash）
--   · 提交幂等索引 IDX_PBCCRC_CUSTOMER_NAME_ID (customer_name, customer_id_card)
--   · 状态扫描索引 IDX_PBCCRC_STATUS (status)
--   以上原本只存在于 db/migration.sql，导致按旧版 schema.sql 建库后
--   ReportMapper 全部查询报 Unknown column 'status'，且 findRecentForUpdate
--   的 SELECT ... FOR UPDATE 因缺索引退化为全表加锁。
--
-- 【存量库升级】不要用本文件重建，改为执行 db/migration.sql（幂等 ALTER）；
--   若目标库已含这四列，跳过该脚本即可。
-- ============================================================================

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_admin_punishment` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '处罚机构',
  `case_no` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '文书编号',
  `punishment` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '处罚内容',
  `reconsider` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '是否行政复议',
  `reconsider_result` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '行政复议结果',
  `open_date` datetime DEFAULT NULL COMMENT '处罚生效时间',
  `deadline_date` datetime DEFAULT NULL COMMENT '处罚截止时间',
  `punishment_amount` decimal(19,2) DEFAULT NULL COMMENT '处罚金额',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_ADMIN_PUNISHMENT_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-公共记录-行政处罚记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_asset_disposal` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `amount` decimal(19,2) DEFAULT NULL COMMENT '金额',
  `balance` decimal(19,2) DEFAULT NULL COMMENT '余额',
  `dispose_date` datetime DEFAULT NULL COMMENT '处理时间',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '接收债权机构',
  `repayment_date` datetime DEFAULT NULL COMMENT '最近一次还款日期',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `record_num` int DEFAULT NULL COMMENT '序号*',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_ASSET_DISPOSAL_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-资产处置信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_ccivil_decision` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '立案法院',
  `case_no` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '案号',
  `reason` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '案由',
  `settle_way` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '结案方式',
  `register_date` datetime DEFAULT NULL COMMENT '立案时间',
  `decision_date` datetime DEFAULT NULL COMMENT '判决/调解生效时间',
  `decision_result` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '判决/调解结果',
  `lawsuit_object` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '诉讼标的',
  `lawsuit_object_amount` decimal(19,2) DEFAULT NULL COMMENT '诉讼标的金额',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_CCIVIL_DECISION_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-公共记录-民事判决信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_compensate` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `dispose_date` datetime DEFAULT NULL COMMENT '处理时间',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '代偿机构',
  `amount` decimal(19,2) DEFAULT NULL COMMENT '金额',
  `balance` decimal(19,2) DEFAULT NULL COMMENT '余额',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `record_num` int DEFAULT NULL COMMENT '序号',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_COMPENSATE_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-保证人代偿信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_court_decision` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '执行法院机构',
  `case_no` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '案号',
  `reason` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '执行案由',
  `settle_way` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '结案方式',
  `register_date` datetime DEFAULT NULL COMMENT '立案时间',
  `settle_date` datetime DEFAULT NULL COMMENT '结案时间(报告上存在"--")',
  `status` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '案件状态',
  `apply_excute_object` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '申请执行标的',
  `already_excute_object` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '已执行标的',
  `apply_excute_amount` decimal(19,2) DEFAULT NULL COMMENT '申请执行标的金额',
  `already_excute_amount` decimal(19,2) DEFAULT NULL COMMENT '已执行标的金额',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_COURT_DECISION_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-公共记录-强制执行记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_credit_card_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `report_id` int NOT NULL COMMENT '报告id',
  `grant_money_date` datetime DEFAULT NULL COMMENT '放款时间',
  `grant_organizations` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '放款单位',
  `currency_type` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '币种',
  `deadline_date` datetime DEFAULT NULL COMMENT '截止时间(年月)',
  `credit_limit_money` decimal(19,2) DEFAULT NULL COMMENT '信用额度',
  `already_use_money` decimal(19,2) DEFAULT NULL COMMENT '已使用额度',
  `overdue_money` decimal(19,2) DEFAULT NULL COMMENT '逾期额度',
  `bad` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '1' COMMENT '是否呆账: 0 是;1 否',
  `overdue_month` int DEFAULT '0' COMMENT '最近5年内有几个月处于逾期状态(单位月)',
  `overdue_month_num` int DEFAULT '0' COMMENT '最近5年内有多少个月逾期超过90天(单位月)',
  `credit_card_sixty` int DEFAULT '0' COMMENT '准贷记卡透支超过60天的月数(单位月)',
  `credit_card_ninety` int DEFAULT '0' COMMENT '准贷记卡透支超过90天的月数(单位月)',
  `active` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '0' COMMENT '是否激活:0 已激活;1未激活',
  `overdraft_balance` decimal(19,2) DEFAULT NULL COMMENT '透支余额',
  `close_account` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '1' COMMENT '是否销户:0已销户;1未销户',
  `type` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '解析类型:0 发生过逾期 1 未发生过逾期',
  `credit_type` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '贷款种类(贷记卡、准贷记卡)',
  `record_num` int DEFAULT NULL COMMENT '序号',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `content` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '内容',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_REPORT_ID_CREDIT_TYPE` (`report_id`,`credit_type`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='征信报告信用卡字段解读表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_credit_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int DEFAULT NULL COMMENT '征信报告id',
  `type` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '类型，1为逾期信用卡，2为未逾期信用卡，11为逾期购房贷款，12为未逾期购房贷款，21为逾期其他贷款，22为未逾期其他贷款，31为他人担保，41资产处置信息，42保证人代偿',
  `content` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '内容',
  `record_num` int DEFAULT NULL COMMENT '序号',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_PBCCRC_CREDIT_RECORD` (`report_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=138 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='人行征信报告-信贷记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_customer_credit` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '注解id',
  `report_id` int DEFAULT NULL COMMENT '报告id',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `result` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '规则判断结果',
  `type` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '是否符合无综合信用条件(0是/1否)',
  `status` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '信用是否良好(0信用良好/1信用不良)',
  `memo` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '备注',
  `credit_limit_money` int DEFAULT NULL COMMENT '贷记卡负债总额度',
  `already_use_money` int DEFAULT NULL COMMENT '贷记卡负债已使用总额度',
  `three_month_query_count` int DEFAULT NULL COMMENT '近三个月查询次数',
  `one_month_query_count` int DEFAULT NULL COMMENT '近一个月查询次数',
  `credit_card` int DEFAULT NULL COMMENT '人民币贷记卡总和(包含正常、逾期、销户)',
  `zcredit_card` int DEFAULT NULL COMMENT '人民币准贷记卡总和',
  `overdue_credit_card` int DEFAULT NULL COMMENT '逾期人民币贷记卡总和(包含逾期、销户)',
  `overdue_credit_card_month` int DEFAULT NULL COMMENT '逾期人民币贷记卡月份数总和(包含逾期、销户)',
  `overdue_credit_card_ninety` int DEFAULT NULL COMMENT '90天逾期人民币贷记卡月份数总和(包含逾期、销户)',
  `overdue_zcredit_card` int DEFAULT NULL COMMENT '逾期人民币准贷记卡总和(包含逾期、销户)',
  `overdue_zcredit_card_month` int DEFAULT NULL COMMENT '逾期人民币准贷记卡月份数总和(包含逾期、销户)',
  `overdue_zcredit_card_ninety` int DEFAULT NULL COMMENT '90天逾期人民币准贷记卡月份数总和(包含逾期、销户)',
  `loan_count` int DEFAULT NULL COMMENT '贷款总数(不包含担保贷款、包含当前已结清)',
  `overdue_loan_count` int DEFAULT NULL COMMENT '逾期贷款总数(不包含担保、包含当前已结清)',
  `overdue_loan_month` int DEFAULT NULL COMMENT '贷款逾期月份数总和(不包含担保贷款、包含当前已结清)',
  `overdue_loan_ninety` int DEFAULT NULL COMMENT '90天以上逾期贷款账户月份数总和(不包含担保贷款、包含当前已结清)',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='用户信用不良判断结果';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_customer_history` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int DEFAULT NULL COMMENT '征信报告id',
  `query_date` datetime NOT NULL COMMENT '查询时间',
  `customer_id_card` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '客户身份证号',
  `customer_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '客户姓名',
  `operator` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '操作人',
  `report_state` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '查询报告状态',
  `customer_id_card_bak` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '身份证',
  `customer_name_bak` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '姓名',
  `id_card` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '身份证后四位',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='客户人信报告查询流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_guarantee` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `guarantee_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '被担保人姓名',
  `id_type` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '证件类型',
  `id_card` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '证件号码',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '担保机构',
  `contract_amount` decimal(19,2) DEFAULT NULL COMMENT '担保贷款合同金额/担保信用卡授信额度',
  `guarantee_amount` decimal(19,2) DEFAULT NULL COMMENT '担保金额',
  `guarantee_date` datetime DEFAULT NULL COMMENT '担保时间',
  `balance` decimal(19,2) DEFAULT NULL COMMENT '余额',
  `deadline_date` datetime DEFAULT NULL COMMENT '截止时间',
  `type` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '担保类型',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `record_num` int DEFAULT NULL COMMENT '序号',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_GUARANTEE_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-个人担保信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_loan_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `report_id` int NOT NULL COMMENT '征信报告id',
  `grant_money_date` datetime DEFAULT NULL COMMENT '放款时间',
  `grant_organizations` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '放款单位',
  `grant_money` decimal(19,2) DEFAULT NULL COMMENT '放款金额',
  `currency_type` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '币种',
  `deadline_date` datetime DEFAULT NULL COMMENT '截止时间(年月)',
  `expire_date` datetime DEFAULT NULL COMMENT '到期时间',
  `settlement_date` datetime DEFAULT NULL COMMENT '结清时间',
  `overdue_money` decimal(19,2) DEFAULT NULL COMMENT '逾期额度',
  `overdue_month` int DEFAULT '0' COMMENT '最近5年内有几个月处于逾期状态(单位月)',
  `overdue_month_num` int DEFAULT '0' COMMENT '最近5年内有多少个月逾期超过90天',
  `settlement` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '1' COMMENT '是否结清 0 是 1否',
  `bad` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '1' COMMENT '是否呆账 0 是 1否',
  `type` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '解析类型:0 发生过逾期 1 未发生过逾期',
  `credit_type` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '贷款种类',
  `record_num` int DEFAULT NULL COMMENT '序号',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `balance` decimal(19,2) DEFAULT NULL COMMENT '余额',
  `loan_type` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '贷款类型(购房贷款、其他贷款)',
  `org_type` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '机构类型(001银行、002信用社、003网商银行、004公司机构)',
  `content` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '内容',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=108 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='征信报告贷款信息字段解读表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_query_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int DEFAULT NULL COMMENT '征信报告id',
  `query_date` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '查询日期',
  `type` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '类型，1为机构查询，2为个人查询',
  `query_no` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '编号',
  `operator` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '查询操作员',
  `query_reason` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '查询原因',
  `query_operator` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '查询操作员(斜杆之前的数据)',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_PBCCRC_QUERY_RECORD` (`report_id`,`type`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=162 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='人行征信报告-查询记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_report` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '解析得到的姓名，非关联客户姓名',
  `cert_type` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '证件类型',
  `cert_no` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '证件号码',
  `query_time` datetime DEFAULT NULL COMMENT '查询时间',
  `report_time` datetime DEFAULT NULL COMMENT '报告时间',
  `marital_status` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '婚姻状况',
  `report_no` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '报告编号',
  `description` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '说明',
  `credit_record_description` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '信贷记录说明',
  `query_record_description` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '查询记录说明',
  `common_record_description` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '公共记录说明',
  `common_record_detail` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '公共记录详细内容',
  `credit_record_summary` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '信贷记录信息概要',
  `credit_card_total` int DEFAULT NULL COMMENT '信用卡-账户数',
  `credit_card_active` int DEFAULT NULL COMMENT '信用卡-未结清/未销户账户数',
  `credit_card_overdue` int DEFAULT NULL COMMENT '信用卡-发生过逾期的账户数',
  `credit_card_serious_overdue` int DEFAULT NULL COMMENT '信用卡-发生过90天以上逾期的账户数',
  `credit_card_guarantee` int DEFAULT NULL COMMENT '信用卡-为他人担保笔数',
  `housing_loan_total` int DEFAULT NULL COMMENT '购房贷款-账户数',
  `housing_loan_active` int DEFAULT NULL COMMENT '购房贷款-未结清/未销户账户数',
  `housing_loan_overdue` int DEFAULT NULL COMMENT '购房贷款-发生过逾期的账户数',
  `housing_loan_serious_overdue` int DEFAULT NULL COMMENT '购房贷款-发生过90天以上逾期的账户数',
  `housing_loan_guarantee` int DEFAULT NULL COMMENT '购房贷款-为他人担保笔数',
  `other_loan_total` int DEFAULT NULL COMMENT '其他贷款-账户数',
  `other_loan_active` int DEFAULT NULL COMMENT '其他贷款-未结清/未销户账户数',
  `other_loan_overdue` int DEFAULT NULL COMMENT '其他贷款-发生过逾期的账户数',
  `other_loan_serious_overdue` int DEFAULT NULL COMMENT '其他贷款-发生过90天以上逾期的账户数',
  `other_loan_guarantee` int DEFAULT NULL COMMENT '其他贷款-为他人担保笔数',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `creator_id` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建者工号',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  `modifier_id` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改者工号',
  `customer_id_card` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '关联客户身份证号',
  `error_msg` text CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '解析出错信息',
  `customer_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '关联客户姓名',
  `asset_total` int DEFAULT NULL COMMENT '资产处置信息数量',
  `repayment_total` int DEFAULT NULL COMMENT '保证人代偿信息数量',
  `app_no` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '申请件编号',
  `customer_id_card_bak` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '身份证',
  `customer_name_bak` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '姓名',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态:PENDING/PARSING/SUCCESS/FAILED',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '解析重试次数',
  `heartbeat_time` datetime DEFAULT NULL COMMENT '解析心跳时间,识别卡死',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '报告正文全文MD5，仅作内容完整性校验（不参与提交幂等）',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_PBCCRC_REPORT_CUSTOMER_ID` (`customer_id_card`) USING BTREE,
  KEY `IDX_PBCCRC_CREATE_TIME` (`create_time`) USING BTREE,
  KEY `IDX_PBCCRC_STATUS` (`status`) USING BTREE,
  KEY `IDX_PBCCRC_CUSTOMER_NAME_ID` (`customer_name`,`customer_id_card`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='人行征信报告';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_report_content` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int DEFAULT NULL COMMENT '征信报告id',
  `content` longtext CHARACTER SET utf8 COLLATE utf8_general_ci COMMENT '前台传入的征信报告html内容',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `REPORT_CONTENT_ID_INDEX` (`report_id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='人行征信报告-原文';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_report_model` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `type` char(1) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '解析类型1、信用卡 ,2、贷款类'',3:资产处置,4:保证人代偿,5:为他人担保,6:公共记录',
  `name` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '字段名称',
  `createdate` datetime DEFAULT NULL COMMENT '创建时间',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=58 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='征信报告字段解析模板主表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_report_model_value` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `report_model_id` int NOT NULL COMMENT '模板表id',
  `value` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '模板表达式值',
  `value_index` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '报文字段解析位置',
  `createdate` datetime DEFAULT NULL COMMENT '创建时间',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=56 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='征信报告字段解析模板对应表达式值';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_report_querylog` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `customer_name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '客户姓名',
  `customer_id_card` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '客户身份证号',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '报告正文全文MD5，仅作内容完整性校验（不参与提交幂等）',
  `create_time` date NOT NULL COMMENT '创建时间',
  `report_id` int DEFAULT NULL COMMENT '人行征信报告id',
  `customer_id_card_bak` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '身份证',
  `customer_name_bak` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '姓名',
  `creator_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `modifier_id` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `T_PBCCRC_REPORT_QUERYLOG_C` (`customer_name`,`customer_id_card`,`content_hash`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='征信查询记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_tax_arrears` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `org` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '主管税务机关',
  `identify_number` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '纳税人识别号',
  `tax_arrears_date` datetime DEFAULT NULL COMMENT '欠税统计时间（年月）',
  `tax_arrears_amount` decimal(19,2) DEFAULT NULL COMMENT '欠税总额',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `creator_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_TAX_ARREARS_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-公共记录-欠税信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_pbccrc_telecom_owe` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `report_id` int NOT NULL COMMENT '关联征信报告id',
  `carrier` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '运营商',
  `type` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '业务类型',
  `charge_date` datetime DEFAULT NULL COMMENT '记账年月（年月）',
  `open_date` datetime DEFAULT NULL COMMENT '业务开通时间（年月）',
  `arrears_amount` decimal(19,2) DEFAULT NULL COMMENT '欠费金额',
  `content` varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '内容',
  `creator_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `modifier_id` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '修改人',
  `modify_time` datetime DEFAULT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `IDX_TELECOM_OWE_REPORT_ID` (`report_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 ROW_FORMAT=DYNAMIC COMMENT='央行征信报告-公共记录-电信欠费信息';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

