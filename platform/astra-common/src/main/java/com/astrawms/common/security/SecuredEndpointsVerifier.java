package com.astrawms.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Deny-by-default guard for writes: the service refuses to start if an AstraWMS endpoint that changes state (anything
 * but GET/HEAD) does not declare its roles with {@code @PreAuthorize}. Reads only need an authenticated tenant user.
 */
public class SecuredEndpointsVerifier implements ApplicationListener<ContextRefreshedEvent> {

    private static final Set<RequestMethod> READS = Set.of(RequestMethod.GET, RequestMethod.HEAD);

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        List<String> unsecured = new ArrayList<>();
        event.getApplicationContext().getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping ->
                mapping.getHandlerMethods().forEach((info, handler) -> {
                    if (isAstra(handler) && writes(info) && !declaresRoles(handler)) {
                        unsecured.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName()
                                + " " + info);
                    }
                }));
        if (!unsecured.isEmpty()) {
            throw new IllegalStateException("Endpoints that change state must declare @PreAuthorize roles: " + unsecured);
        }
    }

    private static boolean isAstra(HandlerMethod handler) {
        return handler.getBeanType().getPackageName().startsWith("com.astrawms");
    }

    private static boolean writes(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() || !READS.containsAll(methods);
    }

    private static boolean declaresRoles(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
    }
}
