package com.rentflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InventoryObservabilityConfigTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void leavesClusterIdDiscoveryEnabledWithoutAnActualOverride(String clusterId) {
        MockEnvironment environment = new MockEnvironment();
        if (clusterId != null) {
            environment.setProperty("inventory.kafka.cluster-id", clusterId);
        }
        KafkaAdmin admin = mock(KafkaAdmin.class);
        BeanPostProcessor processor = InventoryObservabilityConfig.kafkaClusterIdOverride(environment);

        assertThat(processor.postProcessAfterInitialization(admin, "kafkaAdmin"))
                .isSameAs(admin);
        verifyNoInteractions(admin);
    }

    @Test
    void appliesTheConfiguredBrokerIdentityWithoutChangingOtherBeans() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("inventory.kafka.cluster-id", "broker-cluster-id");
        KafkaAdmin admin = mock(KafkaAdmin.class);
        BeanPostProcessor processor = InventoryObservabilityConfig.kafkaClusterIdOverride(environment);

        assertThat(processor.postProcessAfterInitialization(admin, "kafkaAdmin"))
                .isSameAs(admin);
        verify(admin).setClusterId("broker-cluster-id");
        Object unrelatedBean = new Object();
        assertThat(processor.postProcessAfterInitialization(unrelatedBean, "other"))
                .isSameAs(unrelatedBean);
    }
}
