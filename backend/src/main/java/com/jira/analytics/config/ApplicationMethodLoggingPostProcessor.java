package com.jira.analytics.config;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

public class ApplicationMethodLoggingPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ApplicationMethodLoggingPostProcessor.class);
    private static final int MAX_VALUE_LENGTH = 240;
    private static final String APPLICATION_PACKAGE = "com.jira.analytics";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (!shouldLogBean(targetClass)) {
            return bean;
        }

        ProxyFactory proxyFactory = new ProxyFactory(bean);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> logInvocation(targetClass, invocation.getMethod(), invocation.getArguments(), invocation::proceed));
        return proxyFactory.getProxy();
    }

    private Object logInvocation(Class<?> targetClass, Method method, Object[] args, InvocationProceed proceed) throws Throwable {
        String operation = targetClass.getSimpleName() + "." + method.getName();
        String arguments = summarizeArguments(method.getParameters(), args);
        Instant startedAt = Instant.now();

        log.info("START {} args={}", operation, arguments);
        try {
            Object result = proceed.proceed();
            long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
            log.info("END {} durationMs={} result={}", operation, elapsedMs, summarizeValue(result));
            return result;
        } catch (Throwable throwable) {
            long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
            log.error(
                    "FAIL {} durationMs={} errorType={} message={}",
                    operation,
                    elapsedMs,
                    throwable.getClass().getSimpleName(),
                    safeMessage(throwable),
                    throwable
            );
            throw throwable;
        }
    }

    private boolean shouldLogBean(Class<?> targetClass) {
        if (targetClass == null || !targetClass.getPackageName().startsWith(APPLICATION_PACKAGE)) {
            return false;
        }
        return AnnotationUtils.findAnnotation(targetClass, RestController.class) != null
                || AnnotationUtils.findAnnotation(targetClass, Controller.class) != null
                || AnnotationUtils.findAnnotation(targetClass, Service.class) != null
                || AnnotationUtils.findAnnotation(targetClass, Repository.class) != null;
    }

    private String summarizeArguments(java.lang.reflect.Parameter[] parameters, Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < args.length; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            String parameterName = parameters != null && index < parameters.length ? parameters[index].getName() : "arg" + index;
            builder.append(parameterName).append('=');
            if (isSensitiveName(parameterName)) {
                builder.append("[REDACTED]");
            } else {
                builder.append(summarizeValue(args[index]));
            }
        }
        return builder.append(']').toString();
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> "Optional[" + summarizeValue(item) + "]").orElse("Optional.empty");
        }
        if (value instanceof Collection<?> collection) {
            return value.getClass().getSimpleName() + "(size=" + collection.size() + ")";
        }
        if (value instanceof Map<?, ?> map) {
            return value.getClass().getSimpleName() + "(size=" + map.size() + ")";
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            return valueClass.getComponentType().getSimpleName() + "[](length=" + Array.getLength(value) + ")";
        }
        if (isScalar(value)) {
            return truncate(redactSensitiveText(String.valueOf(value)));
        }
        String packageName = valueClass.getPackageName();
        if (packageName.startsWith(APPLICATION_PACKAGE)) {
            return truncate(redactSensitiveText(String.valueOf(value)));
        }
        return valueClass.getSimpleName();
    }

    private boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof java.time.temporal.Temporal;
    }

    private boolean isSensitiveName(String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("authorization")
                || normalized.contains("auth");
    }

    private String redactSensitiveText(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value
                .replaceAll("(?i)(apiToken|password|token|secret|authorization)=([^,)\\]]+)", "$1=[REDACTED]")
                .replaceAll("(?i)(api-token|api_token|password|token|secret|authorization):\\s*([^,)\\]]+)", "$1: [REDACTED]");
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return StringUtils.hasText(message) ? truncate(redactSensitiveText(message)) : throwable.getClass().getSimpleName();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_VALUE_LENGTH) + "...";
    }

    @FunctionalInterface
    private interface InvocationProceed {
        Object proceed() throws Throwable;
    }
}
