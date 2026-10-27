package com.uav.inspection.evidence.cleanup;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods=false)
@Profile("database & storage & cleanup")
@EnableScheduling
public class CleanupSchedulingConfiguration {}
