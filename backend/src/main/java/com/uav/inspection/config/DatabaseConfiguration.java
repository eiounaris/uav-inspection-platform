package com.uav.inspection.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("database")
@MapperScan({"com.uav.inspection.identity.persistence", "com.uav.inspection.device.persistence",
        "com.uav.inspection.audit.persistence", "com.uav.inspection.inspection.persistence",
        "com.uav.inspection.evidence.upload.persistence", "com.uav.inspection.evidence.finalization.persistence",
        "com.uav.inspection.messaging.persistence", "com.uav.inspection.blockchain.anchor.persistence",
        "com.uav.inspection.evidence.verification.persistence", "com.uav.inspection.review.persistence",
        "com.uav.inspection.sharing.persistence"})
public class DatabaseConfiguration {}
