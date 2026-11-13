package com.selectcode.pos.shared.jobs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs and async exports (docs/ARCHITECTURE.md §10). Scheduled methods check {@link Jobs#enabled()}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableAsync
class SchedulingConfig {
}
