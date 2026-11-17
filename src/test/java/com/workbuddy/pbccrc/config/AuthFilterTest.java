package com.workbuddy.pbccrc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.pbccrc.common.OperatorContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 最小鉴权过滤器测试（评审 2.5）：验证关闭时放行、开启时校验 token、放行后注入可信操作人。
 *
 * @author Alan
 * @date 2026-11-17
 */
class AuthFilterTest {

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private PbccrcProperties props(boolean enabled, String token) {
        PbccrcProperties p = new PbccrcProperties();
        p.getSecurity().setEnabled(enabled);
        p.getSecurity().setToken(token);
        return p;
    }

    /** 鉴权关闭时直接放行，不设置操作人上下文。 */
    @Test
    void passesThroughWhenDisabled() throws Exception {
        AuthFilter filter = new AuthFilter(props(false, "secret"), new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/pbccrc/admin/reports/1/detail");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] called = {false};
        FilterChain chain = (r, s) -> called[0] = true;

        filter.doFilter(req, resp, chain);

        assertTrue(called[0]);
        assertEquals(200, resp.getStatus());
        assertNull(OperatorContext.get());
    }

    /** 鉴权开启但 token 错误时返回 401，不进入业务链路。 */
    @Test
    void rejectsWrongToken() throws Exception {
        AuthFilter filter = new AuthFilter(props(true, "secret"), new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/pbccrc/admin/reports/1/detail");
        req.addHeader("X-Auth-Token", "wrong");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] called = {false};
        FilterChain chain = (r, s) -> called[0] = true;

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertFalse(called[0]);
        assertTrue(resp.getContentAsString().contains("401"));
    }

    /** token 正确时放行，并把可信请求头中的操作人写入上下文，链路结束后清理。 */
    @Test
    void acceptsTokenAndInjectsOperator() throws Exception {
        AuthFilter filter = new AuthFilter(props(true, "secret"), new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/pbccrc/admin/reports/query");
        req.addHeader("X-Auth-Token", "Bearer secret");
        req.addHeader("X-Operator", "op-from-gateway");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, (r, s) -> {
            assertEquals("op-from-gateway", OperatorContext.get(), "链路内应能取到可信操作人");
            assertEquals("op-from-gateway", OperatorContext.getOrDefault("body-op"));
        });

        assertEquals(200, resp.getStatus());
        assertNull(OperatorContext.get(), "链路结束后应清理，防止线程复用串号");
    }

    /** 非征信接口不受鉴权影响。 */
    @Test
    void skipsNonPbccrcPaths() throws Exception {
        AuthFilter filter = new AuthFilter(props(true, "secret"), new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean[] called = {false};

        filter.doFilter(req, resp, (r, s) -> called[0] = true);

        assertTrue(called[0]);
        assertEquals(200, resp.getStatus());
    }
}
