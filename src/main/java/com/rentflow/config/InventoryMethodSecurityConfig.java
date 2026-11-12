package com.rentflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.observation.SecurityObservationSettings;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class InventoryMethodSecurityConfig {
    @Bean
    InventoryAuthorizationPolicy inventoryAuthorizationPolicy() {
        return new InventoryAuthorizationPolicy();
    }

    @Bean
    SecurityObservationSettings inventorySecurityObservationSettings() {
        // Use the bounded denial counter/diagnostic, without framework authorization detail spans.
        return SecurityObservationSettings.noObservations();
    }
}
