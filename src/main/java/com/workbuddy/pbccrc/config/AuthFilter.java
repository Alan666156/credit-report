package com.workbuddy.pbccrc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.pbccrc.common.OperatorContext;
import com.workbuddy.pbccrc.common.Result;
import com.workbuddy.pbccrc.common.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理端最小鉴权过滤器（评审 2.5）。
 *
 * <p>默认关闭（{@code pbccrc.security.enabled=false}），开启后对 {@code /api/pbccrc/**}
 * 全部接口校验固定 token（请求头 X-Auth-Token），未授权直接返回 401，不再让征信数据
 * 裸奔。校验通过后从可信请求头（X-Operator）读取操作人写入 {@link OperatorContext}，
 * 业务层据此留痕，避免客户端在请求体里自报工号。</p>
 *
 * <p>这是最小可用方案：生产应叠加内网白名单 / 网关注入身份并妥善保管 token；固定 token
 * 仅解决「零鉴权」问题，不等同完整认证体系。</p>
 *
 * @author Alan
 * @date 2026-11-17
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Auth-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final PbccrcProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 构造方法，由 Spring 注入配置与 JSON 序列化器。
     *
     * @param properties   业务配置
     * @param objectMapper JSON 序列化器
     */
    public AuthFilter(PbccrcProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验 token 并注入可信操作人；鉴权关闭时仅放行。
     *
     * @param request     请求
     * @param response    响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        PbccrcProperties.Security security = properties.getSecurity();
        if (!security.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = security.getToken();
        String presented = request.getHeader(TOKEN_HEADER);
        String normalized = presented != null && presented.startsWith(BEARER_PREFIX)
                ? presented.substring(BEARER_PREFIX.length()) : presented;
        if (token == null || token.isBlank() || !matches(token, normalized)) {
            log.warn("未授权访问 {}，token 校验失败", request.getRequestURI());
            reject(response);
            return;
        }

        // 鉴权通过：操作人取可信请求头，写入上下文供业务层留痕，结束后清理防串用
        OperatorContext.set(request.getHeader(security.getOperatorHeader()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            OperatorContext.clear();
        }
    }

    /**
     * 常量时间比较 token，避免按字节提前返回造成的时序侧信道。
     *
     * @param expected  配置中的正确 token
     * @param presented 请求携带的 token，可为 null
     * @return 一致返回 true
     */
    private static boolean matches(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回 401 未授权 JSON 响应。
     *
     * @param response 响应
     * @throws IOException IO 异常
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.fail(ResultCode.UNAUTHORIZED));
    }

    /**
     * 仅对征信接口鉴权，不影响其它路径。
     *
     * @param request 请求
     * @return 不需要鉴权返回 true
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/pbccrc/");
    }
}
