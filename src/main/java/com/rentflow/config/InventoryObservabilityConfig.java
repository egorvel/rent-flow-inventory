package com.rentflow.config;

import java.time.Clock;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.kafka.core.KafkaAdmin;

import io.micrometer.observation.ObservationPredicate;

@Configuration(proxyBeanMethods = false)
public class InventoryObservabilityConfig {
    @Bean
    Clock inventoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObservationPredicate excludeManagementObservations() {
        return (_, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                String path = request.getCarrier().getRequestURI();
                return !path.startsWith("/actuator") && !path.equals("/readyz") && !path.equals("/livez");
            }
            return true;
        };
    }

    @Bean
    static BeanPostProcessor kafkaClusterIdOverride(Environment environment) {
        String clusterId = environment.getProperty("inventory.kafka.cluster-id");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaAdmin admin && clusterId != null && !clusterId.isBlank()) {
                    admin.setClusterId(clusterId);
                }
                return bean;
            }
        };
    }
}
