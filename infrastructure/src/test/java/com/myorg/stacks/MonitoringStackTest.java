package com.myorg.stacks;

import com.myorg.config.EnvKey;
import com.myorg.config.monitoring.AlloyConfig;
import com.myorg.config.monitoring.GrafanaConfig;
import com.myorg.config.monitoring.LokiConfig;
import com.myorg.config.monitoring.MonitoringConfig;
import com.myorg.config.monitoring.PinpointConfig;
import com.myorg.props.MonitoringStackProps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringStackTest {

    @Test
    @DisplayName("Monitoring 스택은 Kafka UI가 MSK에 접근할 수 있도록 보안 그룹과 IAM 권한을 포함해야 한다.")
    void should_allow_kafka_ui_access_to_msk() {
        App app = new App();
        Environment env = Environment.builder()
                .region("ap-northeast-2")
                .account("123456789012")
                .build();

        Stack fixtureStack = new Stack(
                app,
                "MonitoringFixtureStack",
                StackProps.builder().env(env).build()
        );

        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();

        SecurityGroup dbSg = SecurityGroup.Builder.create(fixtureStack, "TestDbSg")
                .vpc(vpc)
                .build();
        SecurityGroup adminApiSg = SecurityGroup.Builder.create(fixtureStack, "TestAdminApiSg")
                .vpc(vpc)
                .build();
        SecurityGroup customerApiSg = SecurityGroup.Builder.create(fixtureStack, "TestCustomerApiSg")
                .vpc(vpc)
                .build();
        SecurityGroup kafkaBrokerSg = SecurityGroup.Builder.create(fixtureStack, "TestKafkaBrokerSg")
                .vpc(vpc)
                .build();

        MonitoringStack monitoringStack = new MonitoringStack(
                app,
                "MonitoringStackTest",
                StackProps.builder().env(env).build(),
                new MonitoringStackProps(
                        vpc,
                        dbSg,
                        adminApiSg,
                        customerApiSg,
                        kafkaBrokerSg,
                        8080,
                        8081,
                        testMonitoringConfig()
                )
        );

        String templateJson = Template.fromStack(monitoringStack).toJSON().toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) Template.fromStack(monitoringStack).toJSON().get("Resources");
        @SuppressWarnings("unchecked")
        Map<String, Object> grafanaServer = (Map<String, Object>) resources.get("GrafanaServer16662A18");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) grafanaServer.get("Properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> userData = (Map<String, Object>) properties.get("UserData");
        String renderedUserData = (String) userData.get("Fn::Base64");

        assertThat(templateJson)
                .contains("Grafana to MSK IAM")
                .contains("FromPort=9098")
                .contains("kafka-cluster:Connect")
                .contains("kafka-cluster:DeleteTopic")
                .contains("kafka-cluster:AlterTopic")
                .contains("kafka-cluster:AlterTopicDynamicConfiguration")
                .contains("arn:aws:kafka:ap-northeast-2:123456789012:cluster/holliverse-msk/*")
                .contains("arn:aws:kafka:ap-northeast-2:123456789012:topic/holliverse-msk/*")
                .contains("arn:aws:kafka:ap-northeast-2:123456789012:group/holliverse-msk/*")
                .contains("AWS-StartPortForwardingSessionToRemoteHost")
                .contains("admin-api.example.internal")
                .contains("\"localPortNumber\":[\"18080\"]");
        assertThat(renderedUserData.length()).isLessThan(25_600);
    }

    private MonitoringConfig testMonitoringConfig() {
        return new MonitoringConfig(
                EnvKey.MONITORING_INSTANCE_TYPE.getDefaultValue(),
                Integer.parseInt(EnvKey.MONITORING_ROOT_VOLUME_GIB.getDefaultValue()),
                Integer.parseInt(EnvKey.MONITORING_SWAP_SIZE_GIB.getDefaultValue()),
                EnvKey.MONITORING_SUBNET_TYPE.getDefaultValue(),
                EnvKey.MONITORING_SSM_PORT_FORWARD_DOCUMENT.getDefaultValue(),
                EnvKey.MONITORING_DB_SECRET_NAME_PREFIX.getDefaultValue(),
                EnvKey.MONITORING_DB_SECRET_ID.getDefaultValue(),
                EnvKey.MONITORING_DOCKER_NETWORK_NAME.getDefaultValue(),
                EnvKey.MONITORING_PROMETHEUS_CONTAINER_NAME.getDefaultValue(),
                EnvKey.MONITORING_PROMETHEUS_IMAGE.getDefaultValue(),
                Integer.parseInt(EnvKey.MONITORING_PROMETHEUS_PORT.getDefaultValue()),
                EnvKey.MONITORING_PROMETHEUS_SCRAPE_INTERVAL.getDefaultValue(),
                Integer.parseInt(EnvKey.MONITORING_AUTO_DASHBOARD_PANEL_LIMIT.getDefaultValue()),
                EnvKey.MONITORING_PG_EXPORTER_CONTAINER_NAME.getDefaultValue(),
                EnvKey.MONITORING_PG_EXPORTER_IMAGE.getDefaultValue(),
                Integer.parseInt(EnvKey.MONITORING_PG_EXPORTER_PORT.getDefaultValue()),
                EnvKey.MONITORING_PG_EXPORTER_EXCLUDE_DATABASES.getDefaultValue(),
                EnvKey.MONITORING_PG_EXPORTER_JOB_NAME.getDefaultValue(),
                EnvKey.MONITORING_ADMIN_API_JOB_NAME.getDefaultValue(),
                EnvKey.MONITORING_CUSTOMER_API_JOB_NAME.getDefaultValue(),
                EnvKey.MONITORING_ADMIN_API_SERVICE_DNS_LABEL.getDefaultValue(),
                EnvKey.MONITORING_CUSTOMER_API_SERVICE_DNS_LABEL.getDefaultValue(),
                new GrafanaConfig(
                        Integer.parseInt(EnvKey.MONITORING_GRAFANA_PORT.getDefaultValue()),
                        Integer.parseInt(EnvKey.MONITORING_LOCAL_FORWARD_PORT.getDefaultValue()),
                        EnvKey.MONITORING_GRAFANA_REPO_NAME.getDefaultValue(),
                        EnvKey.MONITORING_GRAFANA_REPO_BASE_URL.getDefaultValue(),
                        EnvKey.MONITORING_GRAFANA_REPO_GPG_KEY_URL.getDefaultValue(),
                        EnvKey.MONITORING_GRAFANA_PACKAGE_NAME.getDefaultValue(),
                        EnvKey.MONITORING_GRAFANA_SERVICE_NAME.getDefaultValue(),
                        "grafana-admin",
                        "grafana-password"
                ),
                new PinpointConfig(
                        EnvKey.MONITORING_PINPOINT_REPO_URL.getDefaultValue(),
                        EnvKey.MONITORING_PINPOINT_REPO_DIR.getDefaultValue(),
                        EnvKey.MONITORING_PINPOINT_VERSION.getDefaultValue(),
                        EnvKey.MONITORING_PINPOINT_GIT_REF.getDefaultValue(),
                        Integer.parseInt(EnvKey.MONITORING_PINPOINT_WEB_PORT.getDefaultValue()),
                        Integer.parseInt(EnvKey.MONITORING_PINPOINT_LOCAL_FORWARD_PORT.getDefaultValue())
                ),
                new AlloyConfig(
                        EnvKey.MONITORING_ALLOY_CONTAINER_NAME.getDefaultValue(),
                        EnvKey.MONITORING_ALLOY_IMAGE.getDefaultValue(),
                        EnvKey.MONITORING_ALLOY_ECS_LOG_GROUPS.getDefaultValue(),
                        EnvKey.MONITORING_ALLOY_LOG_ENV.getDefaultValue()
                ),
                new LokiConfig(
                        EnvKey.MONITORING_LOKI_CONTAINER_NAME.getDefaultValue(),
                        EnvKey.MONITORING_LOKI_IMAGE.getDefaultValue(),
                        Integer.parseInt(EnvKey.MONITORING_LOKI_PORT.getDefaultValue()),
                        EnvKey.MONITORING_LOKI_S3_BUCKET.getDefaultValue(),
                        EnvKey.MONITORING_LOKI_S3_PREFIX.getDefaultValue(),
                        Integer.parseInt(EnvKey.MONITORING_LOKI_TRACE_DEBUG_RETENTION_HOURS.getDefaultValue()),
                        Integer.parseInt(EnvKey.MONITORING_LOKI_WARN_RETENTION_HOURS.getDefaultValue()),
                        Integer.parseInt(EnvKey.MONITORING_LOKI_ERROR_RETENTION_HOURS.getDefaultValue()),
                        Integer.parseInt(EnvKey.MONITORING_LOKI_FATAL_RETENTION_HOURS.getDefaultValue())
                )
        );
    }
}
