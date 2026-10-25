package com.uav.inspection.device;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Profile("database")
public final class DevicePresenceSettings {
    private final Duration onlineThreshold;
    private final Duration cacheTtl;
    private final String keyPrefix;
    public DevicePresenceSettings(Environment environment) {
        long threshold=environment.getProperty("inspection.device.online-threshold-seconds",Long.class,20L);
        long ttl=environment.getProperty("inspection.device.presence-cache-ttl-seconds",Long.class,60L);
        keyPrefix=environment.getProperty("inspection.device.presence-key-prefix","inspection:device:seen:");
        if(threshold<1 || threshold>3600 || ttl<threshold || ttl>86400 || keyPrefix==null
                || !keyPrefix.matches("[A-Za-z0-9:_-]{1,160}")) throw new IllegalStateException("Invalid device presence limits");
        onlineThreshold=Duration.ofSeconds(threshold); cacheTtl=Duration.ofSeconds(ttl);
    }
    public Duration onlineThreshold(){return onlineThreshold;}
    public Duration cacheTtl(){return cacheTtl;}
    public String keyPrefix(){return keyPrefix;}
}
