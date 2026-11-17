package com.workbuddy.pbccrc.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.workbuddy.pbccrc.common.OperatorContext;
import com.workbuddy.pbccrc.config.PbccrcProperties;
import com.workbuddy.pbccrc.dao.ReportContentMapper;
import com.workbuddy.pbccrc.dao.ReportMapper;
import com.workbuddy.pbccrc.dao.ReportQueryLogMapper;
import com.workbuddy.pbccrc.model.dto.SubmitRequest;
import com.workbuddy.pbccrc.model.entity.Report;
import com.workbuddy.pbccrc.model.entity.ReportContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 回归护栏：提交幂等键必须只由「证件号 + 姓名」推导，报告正文哈希不得混入。
 *
 * <p>这是针对历史事故写死的断言——曾把幂等键改成正文全文 MD5，等于「正文不同即可重复上传」，
 * 直接违背「7 天内同一姓名+身份证不许重复提交」的硬约束。此测试把该错误钉死，任何人
 * 不得为了让测试变绿而改写断言——规则见项目根目录 REVIEW.md。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
class SubmitServiceKeyTest {

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    @Test
    void idempotencyKeyUsesIdentityOnlyNeverContentHash() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(anyString());
        when(bucket.trySet(any(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReportMapper reportMapper = mock(ReportMapper.class);
        when(reportMapper.findRecentForUpdate(anyString(), anyString(), any())).thenReturn(null);
        when(reportMapper.insert(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            r.setId(1);
            return 1;
        });
        ReportContentMapper contentMapper = mock(ReportContentMapper.class);
        when(contentMapper.insert(any(ReportContent.class))).thenReturn(1);
        ReportQueryLogMapper logMapper = mock(ReportQueryLogMapper.class);
        when(logMapper.insertIgnore(anyString(), anyString(), anyInt(), anyString())).thenReturn(1);

        SubmitServiceImpl service = new SubmitServiceImpl(
                reportMapper, contentMapper, logMapper, new PbccrcProperties(), client);

        String name = "张三";
        String id = "110101199001011234";
        String expectedKey = "pbccrc:submit:lock:" + DigestUtil.md5Hex(id + "|" + name);

        SubmitRequest a = new SubmitRequest();
        a.setCustomerName(name);
        a.setCustomerIdCard(id);
        a.setHtmlContent("<html>正文 A</html>");
        service.submit(a);

        SubmitRequest b = new SubmitRequest();
        b.setCustomerName(name);
        b.setCustomerIdCard(id);
        b.setHtmlContent("<html>完全不同的正文 B</html>");
        service.submit(b);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).getBucket(keys.capture());
        assertEquals(expectedKey, keys.getAllValues().get(0), "幂等键必须 = md5(证件|姓名)");
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1), "正文不同不得改变幂等键");
        assertFalse(keys.getAllValues().get(0).contains(DigestUtil.md5Hex(a.getHtmlContent())),
                "幂等键不得包含正文哈希");
    }
}
