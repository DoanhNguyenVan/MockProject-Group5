package com.boxcinema.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnExpression("${app.scheduling.enabled:true} and ${app.cleanup.pending-accounts.scheduling-enabled:true}")
public class SchedulingConfig {
}

