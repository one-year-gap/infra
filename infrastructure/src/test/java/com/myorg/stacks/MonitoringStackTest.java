package com.myorg.stacks;

import com.myorg.config.monitoring.MonitoringConfig;
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
                        MonitoringConfig.fromEnv()
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
                .contains("arn:aws:kafka:ap-northeast-2:123456789012:cluster/holliverse-msk-serverless/*")
                .contains("arn:aws:kafka:ap-northeast-2:123456789012:topic/holliverse-msk-serverless/*")
                .contains("arn:aws:kafka:ap-northeast-2:123456789012:group/holliverse-msk-serverless/*");
        assertThat(renderedUserData.length()).isLessThan(25_600);
    }
}
