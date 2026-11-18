package com.workbuddy.pbccrc.dao;

import com.workbuddy.pbccrc.model.dto.ReportVO;
import com.workbuddy.pbccrc.model.entity.Report;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告主表数据访问接口。
 *
 * <p>覆盖持久化、状态机流转与后台条件查询。SQL 集中于
 * {@code resources/mapper/ReportMapper.xml}：状态流转通过带 {@code status} 条件的
 * CAS 更新实现（抢占 claim、心跳 heartbeat、重置 resetToPending、成功/失败标记），
 * 调用方必须检查返回行数——0 行表示状态已被他人改动，而非无操作。</p>
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
    Report findById(@Param("id") Integer id);

    /**
     * 插入报告主表记录。
     *
     * @param report 报告实体
     * @return 影响行数，成功为 1
     */
    int insert(Report report);

    /**
     * CAS 抢占报告：仅待解析或失败状态可抢占为解析中。
     *
     * @param id 报告主键
     * @return 影响行数，1 表示抢占成功，0 表示已被抢占或状态不符
     */
    int claim(@Param("id") Integer id);

    /**
     * 刷新解析中心跳时间：仅解析中状态生效。
     *
     * @param id 报告主键
     * @return 影响行数
     */
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
    int markSuccess(Report report);

    /**
     * 标记解析失败并递增重试次数。
     *
     * <p>同样带 {@code status='PARSING'} 守卫：看门狗判超时发生在 {@code markSuccess}
     * 提交之后时，本条 CAS 返回 0，不会把刚成功的报告改回 FAILED，从而避免一次
     * 无意义的重解析与误告警。</p>
     *
     * @param id       报告主键
     * @param errorMsg 失败原因
     * @return 影响行数，0 表示状态已被他人改动
     */
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
    int resetFailedToPending(@Param("id") Integer id);

    /**
     * 查询心跳超时的卡死报告主键。
     *
     * @param minutes 心跳超时阈值（分钟）
     * @return 卡死报告主键列表
     */
    List<Integer> findStuck(@Param("minutes") int minutes);

    /**
     * 查询可重试的失败报告主键。
     *
     * @param maxRetry 最大重试次数上限
     * @return 重试次数未达上限的失败报告主键列表
     */
    List<Integer> findRetryable(@Param("maxRetry") int maxRetry);

    /**
     * 查询进入死信的报告。
     *
     * @param maxRetry 最大重试次数上限
     * @return 失败且重试次数已达上限的报告列表
     */
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
    List<ReportVO> findByCustomer(@Param("customerIdCard") String customerIdCard,
                                  @Param("customerName") String customerName,
                                  @Param("offset") int offset,
                                  @Param("pageSize") int pageSize);

    /**
     * 统计指定证件号加姓名的报告总数。
     *
     * @param customerIdCard 证件号
     * @param customerName   姓名
     * @return 报告总数
     */
    long countByCustomer(@Param("customerIdCard") String customerIdCard,
                         @Param("customerName") String customerName);

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
    Integer findRecentForUpdate(@Param("customerName") String customerName,
                                @Param("customerIdCard") String customerIdCard,
                                @Param("since") LocalDateTime since);

    /**
     * 按主键查询报告视图。
     *
     * @param id 报告主键
     * @return 报告视图，无记录返回 null
     */
    ReportVO findVOById(@Param("id") Integer id);

    /**
     * 后台管理：按可选条件统计报告总数。
     *
     * <p>过滤条件与 {@link #findByCondition} 共用 XML 中的 {@code AdminWhere} 片段，
     * 保证总数与列表口径一致。</p>
     *
     * @param customerName    用户名，模糊匹配，可为空
     * @param name            姓名，模糊匹配，可为空
     * @param createTimeStart 创建时间起始，包含，可为空
     * @param createTimeEnd   创建时间截止，包含，可为空
     * @return 匹配记录总数
     */
    long countByCondition(@Param("customerName") String customerName,
                          @Param("name") String name,
                          @Param("createTimeStart") String createTimeStart,
                          @Param("createTimeEnd") String createTimeEnd);

    /**
     * 后台管理：按可选条件分页查询报告视图。
     *
     * @param customerName    用户名，模糊匹配，可为空
     * @param name            姓名，模糊匹配，可为空
     * @param createTimeStart 创建时间起始，包含，可为空
     * @param createTimeEnd   创建时间截止，包含，可为空
     * @param offset          分页偏移量
     * @param pageSize        每页条数
     * @return 报告视图列表
     */
    List<ReportVO> findByCondition(@Param("customerName") String customerName,
                                   @Param("name") String name,
                                   @Param("createTimeStart") String createTimeStart,
                                   @Param("createTimeEnd") String createTimeEnd,
                                   @Param("offset") int offset,
                                   @Param("pageSize") int pageSize);
}
