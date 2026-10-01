package com.astrawms.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.common.LiteralExpression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Evaluates an endpoint's role-only {@code @PreAuthorize} rule before the request body is read and validated, so an
 * unauthorised caller gets 403 and learns nothing about the payload rules (method security alone would answer 400
 * first). Method security still applies afterwards; rules that refer to method arguments are left to it.
 */
public class EarlyRoleCheckInterceptor implements HandlerInterceptor {

    private static final Expression NONE = new LiteralExpression("");

    private final DefaultMethodSecurityExpressionHandler expressions = new DefaultMethodSecurityExpressionHandler();
    private final Map<Method, Expression> cache = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Expression rule = cache.computeIfAbsent(method.getMethod(), m -> parse(method));
        if (rule == NONE) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        var context = expressions.createEvaluationContext(() -> auth,
                new SimpleMethodInvocation(method.getBean(), method.getMethod()));
        if (!ExpressionUtils.evaluateAsBoolean(rule, context)) {
            throw new AccessDeniedException("Access denied");
        }
        return true;
    }

    private Expression parse(HandlerMethod method) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), PreAuthorize.class);
        }
        if (rule == null || rule.value().contains("#")) {
            return NONE;
        }
        return expressions.getExpressionParser().parseExpression(rule.value());
    }
}
