package com.uav.inspection.config;

import com.uav.inspection.device.DevicePresenceCache;
import com.uav.inspection.device.DevicePresenceSettings;
import com.uav.inspection.device.RedisDevicePresenceCache;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** Redis is optional at runtime; a failed cache command cannot roll back accepted device data. */
@Configuration(proxyBeanMethods=false)
@Profile("database")
public class DeviceRedisConfiguration {
    @Bean(destroyMethod="destroy")
    LettuceConnectionFactory deviceRedisConnectionFactory(Environment environment) {
        String host=environment.getProperty("spring.data.redis.host","127.0.0.1");
        int port=environment.getProperty("spring.data.redis.port",Integer.class,6379);
        if(host==null || host.isBlank() || port<1 || port>65535) throw new IllegalStateException("Invalid Redis connection address");
        var standalone=new RedisStandaloneConfiguration(host,port);
        String password=environment.getProperty("spring.data.redis.password");
        if(password!=null && !password.isBlank()) standalone.setPassword(password);
        var options=ClientOptions.builder().disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build()).build();
        var client=LettuceClientConfiguration.builder().clientOptions(options).commandTimeout(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ofMillis(100)).build();
        return new LettuceConnectionFactory(standalone,client);
    }
    @Bean
    StringRedisTemplate deviceRedisTemplate(LettuceConnectionFactory deviceRedisConnectionFactory) {
        return new StringRedisTemplate(deviceRedisConnectionFactory);
    }
    @Bean
    DevicePresenceCache devicePresenceCache(StringRedisTemplate deviceRedisTemplate,DevicePresenceSettings settings) {
        return new RedisDevicePresenceCache(deviceRedisTemplate,settings);
    }
}
