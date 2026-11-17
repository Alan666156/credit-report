package com.workbuddy.pbccrc.service;

import com.workbuddy.pbccrc.common.BizException;
import com.workbuddy.pbccrc.model.dto.SubmitRequest;

/**
 * 征信报告提交服务。
 *
 * <p>先存原文后异步解析，采用 Redis 原子锁加数据库唯一键双保险实现幂等。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
public interface SubmitService {

    /**
     * 提交一份征信报告。
     *
     * <p>核心流程：1）以证件号和姓名计算身份幂等键；2）通过 Redis 原子锁去重（必需依赖）；
     * 3）事务内二次校验并插入报告主表、报告原文与客户查询流水；4）任一重复或异常则回滚并删除锁。</p>
     *
     * @param req 提交请求，含证件号、姓名、申请编号、HTML 原文及操作人
     * @return 新生成报告的主键 reportId
     * @throws BizException 当报告重复提交时抛出
     */
    Integer submit(SubmitRequest req);
}
