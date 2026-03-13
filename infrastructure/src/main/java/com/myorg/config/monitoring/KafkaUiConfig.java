package com.myorg.config.monitoring;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;

/**
 * Kafka UI 운영 설정값.
 */
public record KafkaUiConfig(
        String containerName,
        String image,
        int kafkaUiPort,
        int localForwardPort
) {
    public static KafkaUiConfig fromEnv() {
        return new KafkaUiConfig(
                AppConfig.getValueOrDefault(EnvKey.MONITORING_KAFKA_UI_CONTAINER_NAME),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_KAFKA_UI_IMAGE),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_KAFKA_UI_PORT)),
                Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.MONITORING_KAFKA_UI_LOCAL_FORWARD_PORT))
        );
    }

    // SSM Kafka UI 포트포워딩 파라미터 JSON.
    public String ssmPortForwardParametersJson() {
        return String.format(
                "{\"portNumber\":[\"%d\"],\"localPortNumber\":[\"%d\"]}",
                kafkaUiPort,
                localForwardPort
        );
    }
}
