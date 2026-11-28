package com.pair.infra.ai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Select the network client only when an actual inference URL is configured. */
public final class InferenceUrlConfigured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("pair.vision.inference-url", "");
        return !value.isBlank();
    }
}
