package com.threeai.nats.camunda.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@ConditionalOnClass(name = "org.camunda.bpm.engine.ProcessEngine")
@EnableConfigurationProperties(CamundaNatsProperties.class)
public class CamundaNatsAutoConfiguration {
    // Beans will be added in Task 7
}
