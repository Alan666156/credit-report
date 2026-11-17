package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.dto.ReportVO;
import com.workbuddy.pbccrc.model.entity.Report;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告主表数据访问接口。
 *
 * <p>覆盖持久化、状态机流转与后台按证件号加姓名查询。状态流转通过带状态条件的
 * CAS 更新实现：抢占（claim）、心跳（heartbeat）、重置为待解析（resetToPending），
 * 以及成功/失败标记，保证多节点并发下的状态一致性。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface ReportMapper {

    /**
     * 按主键查询报告主表记录。
     *
     * @param id 报告主键
     * @return 报告实体，无记录返回 null
     */
    @Select("SELECT * FROM t_pbccrc_report WHERE id = #{id}")
    Report findById(@Param("id") Integer id);

    /**
     * 插入报告主表记录。
     *
     * @param report 报告实体
     * @return 影响行数，成功为 1
     */
    @Insert("INSERT INTO t_pbccrc_report (customer_id_card, customer_name, app_no, status, retry_count, heartbeat_time, content_hash, create_time, creator_id) VALUES (#{customerIdCard}, #{customerName}, #{appNo}, #{status}, #{retryCount}, #{heartbeatTime}, #{contentHash}, #{createTime}, #{creatorId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Report report);

    /**
     * CAS 抢占报告：仅待解析或失败状态可抢占为解析中。
     *
     * @param id 报告主键
     * @return 影响行数，1 表示抢占成功，0 表示已被抢占或状态不符
     */
    @Update("UPDATE t_pbccrc_report SET status = 'PARSING', heartbeat_time = NOW(), error_msg = NULL WHERE id = #{id} AND status IN ('PENDING','FAILED')")
    int claim(@Param("id") Integer id);

    /**
     * 刷新解析中心跳时间：仅解析中状态生效。
     *
     * @param id 报告主键
     * @return 影响行数
     */
    @Update("UPDATE t_pbccrc_report SET heartbeat_time = NOW() WHERE id = #{id} AND status = 'PARSING'")
    int heartbeat(@Param("id") Integer id);

    /**
     * 回填解析汇总结果并将状态置为成功。
     *
     * <p>带 {@code status='PARSING'} 的 CAS 条件：仅当前仍由本解析流程占有的报告可被
     * 标记为成功。若已被看门狗判超时并置 FAILED，则影响 0 行，由调用方回滚并告警，
     * 避免迟到结果覆盖失败状态（评审 3.6）。</p>
     *
     * @param report 含汇总字段与主键的报告实体
     * @return 影响行数，0 表示主表状态已变更
     */
    @Update("UPDATE t_pbccrc_report SET name=#{name}, cert_type=#{certType}, cert_no=#{certNo}, query_time=#{queryTime}, report_time=#{reportTime}, marital_status=#{maritalStatus}, report_no=#{reportNo}, description=#{description}, credit_record_description=#{creditRecordDescription}, query_record_description=#{queryRecordDescription}, common_record_description=#{commonRecordDescription}, common_record_detail=#{commonRecordDetail}, credit_record_summary=#{creditRecordSummary}, credit_card_total=#{creditCardTotal}, credit_card_active=#{creditCardActive}, credit_card_overdue=#{creditCardOverdue}, credit_card_serious_overdue=#{creditCardSeriousOverdue}, credit_card_guarantee=#{creditCardGuarantee}, housing_loan_total=#{housingLoanTotal}, housing_loan_active=#{housingLoanActive}, housing_loan_overdue=#{housingLoanOverdue}, housing_loan_serious_overdue=#{housingLoanSeriousOverdue}, housing_loan_guarantee=#{housingLoanGuarantee}, other_loan_total=#{otherLoanTotal}, other_loan_active=#{otherLoanActive}, other_loan_overdue=#{otherLoanOverdue}, other_loan_serious_overdue=#{otherLoanSeriousOverdue}, other_loan_guarantee=#{otherLoanGuarantee}, asset_total=#{assetTotal}, repayment_total=#{repaymentTotal}, status='SUCCESS', error_msg=#{errorMsg}, heartbeat_time=NOW(), modify_time=NOW() WHERE id=#{id} AND status='PARSING'")
    int markSuccess(Report report);

    /**
     * 标记解析失败并递增重试次数。
     *
     * @param id      报告主键
     * @param errorMsg 失败原因
     * @return 影响行数
     */
    @Update("UPDATE t_pbccrc_report SET status='FAILED', error_msg=#{errorMsg}, retry_count = retry_count + 1, modify_time=NOW() WHERE id=#{id}")
    int markFailed(@Param("id") Integer id, @Param("errorMsg") String errorMsg);

    /**
     * 将报告重置为待解析：待解析、解析中或失败状态均可重置。
     *
     * <p>包含 PENDING 是关键：findStuck 会捞出心跳超时的 PENDING 报告（如提交后异步任务
     * 未被派发、进程在派发前退出），若这里不接受 PENDING，重置返回 0，补偿任务会每轮空转，
     * 报告永久卡死。重置时同步刷新心跳，使重投后的报告重新进入超时计时。</p>
     *
     * @param id 报告主键
     * @return 影响行数，1 表示重置成功
     */
    @Update("UPDATE t_pbccrc_report SET status='PENDING', heartbeat_time=NOW(), modify_time=NOW() WHERE id=#{id} AND status IN ('PENDING','PARSING','FAILED')")
    int resetToPending(@Param("id") Integer id);

    /**
     * 手动重试专用重置：仅失败状态可重置为待解析，拒绝解析中的报告。
     *
     * <p>评审 3.3：旧的手动重试直接复用 resetToPending，允许把 PARSING 重置为 PENDING，
     * 而正在跑的线程仍持有 PARSING（分布式锁在解析期间不会释放），新任务又能 claim 到
     * PENDING，导致同一报告两个线程同时删改明细。这里把手动重试限制为仅 FAILED，
     * PARSING 由看门狗和补偿任务处理，避免并发重复解析。</p>
     *
     * @param id 报告主键
     * @return 影响行数，1 表示重置成功
     */
    @Update("UPDATE t_pbccrc_report SET status='PENDING', heartbeat_time=NOW(), modify_time=NOW() WHERE id=#{id} AND status='FAILED'")
    int resetFailedToPending(@Param("id") Integer id);

    /**
     * 查询心跳超时的卡死报告主键。
     *
     * @param minutes 心跳超时阈值（分钟）
     * @return 卡死报告主键列表
     */
    @Select("SELECT id FROM t_pbccrc_report WHERE status IN ('PENDING','PARSING') AND heartbeat_time < DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)")
    List<Integer> findStuck(@Param("minutes") int minutes);

    /**
     * 查询可重试的失败报告主键。
     *
     * @param maxRetry 最大重试次数上限
     * @return 重试次数未达上限的失败报告主键列表
     */
    @Select("SELECT id FROM t_pbccrc_report WHERE status='FAILED' AND retry_count < #{maxRetry}")
    List<Integer> findRetryable(@Param("maxRetry") int maxRetry);

    /**
     * 查询进入死信的报告。
     *
     * @param maxRetry 最大重试次数上限
     * @return 失败且重试次数已达上限的报告列表
     */
    @Select("SELECT id, customer_name, customer_id_card, report_no, query_time FROM t_pbccrc_report WHERE status='FAILED' AND retry_count >= #{maxRetry} ORDER BY modify_time DESC")
    List<Report> findDeadLetters(@Param("maxRetry") int maxRetry);

    /**
     * 按证件号加姓名分页查询报告视图。
     *
     * @param customerIdCard 证件号
     * @param customerName   姓名
     * @param offset         分页偏移量
     * @param pageSize       每页条数
     * @return 报告视图列表
     */
    @Select("SELECT id, customer_name, customer_id_card, name, cert_no, report_no, query_time, report_time, status, retry_count, error_msg, app_no, create_time FROM t_pbccrc_report WHERE customer_id_card = #{customerIdCard} AND customer_name = #{customerName} ORDER BY create_time DESC LIMIT #{offset}, #{pageSize}")
    List<ReportVO> findByCustomer(@Param("customerIdCard") String customerIdCard, @Param("customerName") String customerName, @Param("offset") int offset, @Param("pageSize") int pageSize);

    /**
     * 统计指定证件号加姓名的报告总数。
     *
     * @param customerIdCard 证件号
     * @param customerName   姓名
     * @return 报告总数
     */
    @Select("SELECT COUNT(1) FROM t_pbccrc_report WHERE customer_id_card = #{customerIdCard} AND customer_name = #{customerName}")
    long countByCustomer(@Param("customerIdCard") String customerIdCard, @Param("customerName") String customerName);

    /**
     * 查询指定姓名与证件号在给定时间点之后最近的一条报告主键（提交幂等二次校验）。
     *
     * <p>在事务内以 {@code FOR UPDATE} 锁定近 N 天的同身份记录：命中说明窗口内已提交过，
     * 拒绝重复提交；未命中则允许本次提交。该查询与 Redis 原子锁（必需依赖）并行生效。
     * 窗口起点由调用方
     * 按 {@code 当前时间 - submitLockTtlDays 天} 传入，保证「7 天内同一姓名+证件不允许
     * 重复提交，7 天后可再次提交」的语义。</p>
     *
     * @param customerName   客户姓名
     * @param customerIdCard 客户证件号
     * @param since          窗口起点时间，早于该时间的记录不算重复
     * @return 窗口内最近一条报告主键，无记录返回 null
     */
    @Select("SELECT id FROM t_pbccrc_report WHERE customer_name = #{customerName} AND customer_id_card = #{customerIdCard} AND create_time >= #{since} ORDER BY create_time DESC LIMIT 1 FOR UPDATE")
    Integer findRecentForUpdate(@Param("customerName") String customerName,
                                @Param("customerIdCard") String customerIdCard,
                                @Param("since") LocalDateTime since);

    /**
     * 按主键查询报告视图。
     *
     * @param id 报告主键
     * @return 报告视图，无记录返回 null
     */
    @Select("SELECT id, customer_name, customer_id_card, name, cert_no, report_no, query_time, report_time, status, retry_count, error_msg, app_no, create_time FROM t_pbccrc_report WHERE id=#{id}")
    ReportVO findVOById(@Param("id") Integer id);
}
