package com.pair.infra.ai;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Keep the in-process fake only for local setups without an inference endpoint. */
public final class InferenceUrlMissing implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return context.getEnvironment().getProperty("pair.vision.inference-url", "").isBlank();
    }
}
