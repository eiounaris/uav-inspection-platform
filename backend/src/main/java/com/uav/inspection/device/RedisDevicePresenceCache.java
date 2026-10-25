package com.uav.inspection.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.List;

public final class RedisDevicePresenceCache implements DevicePresenceCache {
    private static final Logger LOG=LoggerFactory.getLogger(RedisDevicePresenceCache.class);
    private static final DefaultRedisScript<Long> REMEMBER=new DefaultRedisScript<>("""
            local old=redis.call('GET',KEYS[1])
            local incoming=tonumber(ARGV[1])
            if not old or not tonumber(old) or tonumber(old)<incoming then
                redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2])
                return 1
            end
            return 0
            """,Long.class);
    private final StringRedisTemplate redis;
    private final DevicePresenceSettings settings;
    public RedisDevicePresenceCache(StringRedisTemplate redis,DevicePresenceSettings settings){this.redis=redis;this.settings=settings;}
    @Override public Instant find(String droneId) {
        try {
            String value=redis.opsForValue().get(settings.keyPrefix()+droneId);
            return value==null?null:Instant.ofEpochMilli(Long.parseLong(value));
        } catch(RuntimeException failure) { LOG.debug("Device presence cache read unavailable: {}",failure.getClass().getSimpleName());return null; }
    }
    @Override public void remember(String droneId,Instant receivedAt) {
        if(receivedAt==null) return;
        try {
            redis.execute(REMEMBER,List.of(settings.keyPrefix()+droneId),Long.toString(receivedAt.toEpochMilli()),Long.toString(settings.cacheTtl().toMillis()));
        } catch(RuntimeException failure) { LOG.debug("Device presence cache write unavailable: {}",failure.getClass().getSimpleName()); }
    }
}
