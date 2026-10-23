package com.uav.inspection.config;

import com.uav.inspection.evidence.finalization.FinalizationProcessor;
import com.uav.inspection.blockchain.anchor.AnchorProcessor;
import com.uav.inspection.messaging.AnchorMessageListener;
import com.uav.inspection.messaging.FinalizationMessageListener;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("messaging")
@EnableScheduling
public class MessagingConfiguration {
    @Bean
    static BeanFactoryPostProcessor messagingProfileGuard(Environment environment) {
        return factory -> {
            if (!environment.acceptsProfiles(Profiles.of("database & storage"))) {
                throw new IllegalStateException("Messaging requires database and storage profiles");
            }
        };
    }

    @Bean(destroyMethod = "destroy")
    CachingConnectionFactory rabbitConnectionFactory(Environment environment) {
        var connection = new CachingConnectionFactory(
                environment.getRequiredProperty("inspection.messaging.host"),
                environment.getRequiredProperty("inspection.messaging.port", Integer.class));
        connection.setUsername(required(environment, "username"));
        connection.setPassword(required(environment, "password"));
        connection.setVirtualHost(environment.getProperty("inspection.messaging.virtual-host", "/"));
        connection.setConnectionTimeout(5000);
        connection.setRequestedHeartBeat(30);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        return connection;
    }

    @Bean
    RabbitTemplate rabbitTemplate(CachingConnectionFactory connection) {
        var template = new RabbitTemplate(connection);
        template.setMandatory(true);
        return template;
    }

    @Bean
    Declarables inspectionQueues(Environment environment) {
        var exchange = new DirectExchange(required(environment, "exchange"), true, false);
        var dead = new DirectExchange(required(environment, "dead-letter-exchange"), true, false);
        var finalize = QueueBuilder.durable(required(environment, "finalize-queue"))
                .deadLetterExchange(dead.getName()).deadLetterRoutingKey("rejected").build();
        var anchor = QueueBuilder.durable(required(environment, "anchor-queue"))
                .deadLetterExchange(dead.getName()).deadLetterRoutingKey("rejected").build();
        var rejected = QueueBuilder.durable(required(environment, "dead-letter-queue")).build();
        return new Declarables(exchange, dead, finalize, anchor, rejected,
                BindingBuilder.bind(finalize).to(exchange).with("file.finalize"),
                BindingBuilder.bind(anchor).to(exchange).with("evidence.anchor"),
                BindingBuilder.bind(rejected).to(dead).with("rejected"));
    }

    @Bean
    RabbitAdmin rabbitAdmin(CachingConnectionFactory connection) { return new RabbitAdmin(connection); }

    @Bean
    SimpleMessageListenerContainer finalizationContainer(CachingConnectionFactory connection,
            FinalizationProcessor processor, Environment environment, RabbitAdmin admin) {
        var container = new SimpleMessageListenerContainer(connection);
        container.setQueueNames(required(environment, "finalize-queue"));
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setRecoveryInterval(5000);
        container.setMessageListener(new FinalizationMessageListener(processor));
        container.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(MessagingConfiguration.class)
                .warn("Messaging consumer recovery required: {}", failure.getClass().getSimpleName()));
        return container;
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("inspection-background-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }

    @Bean
    @Profile("ledger")
    SimpleMessageListenerContainer anchorContainer(CachingConnectionFactory connection,
            AnchorProcessor processor, Environment environment, RabbitAdmin admin) {
        var container = new SimpleMessageListenerContainer(connection);
        container.setQueueNames(required(environment, "anchor-queue"));
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(1);
        container.setConcurrentConsumers(1);
        container.setRecoveryInterval(5000);
        container.setMessageListener(new AnchorMessageListener(processor));
        container.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(MessagingConfiguration.class)
                .warn("Anchor consumer recovery required: {}", failure.getClass().getSimpleName()));
        return container;
    }

    private static String required(Environment environment, String key) {
        String value = environment.getRequiredProperty("inspection.messaging." + key);
        if (value.isBlank()) throw new IllegalStateException("Missing messaging setting");
        return value;
    }
}
