package com.myorg.stacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.s3.Bucket;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MskConnectStackTest {

    @Test
    @DisplayName("MSK Connect 스택 기본 리소스가 생성되어야 한다.")
    void should_create_msk_connect_resources() {
        App app = new App();
        StackProps stackProps = StackProps.builder()
                .env(Environment.builder()
                        .account("123456789012")
                        .region("ap-northeast-2")
                        .build())
                .build();
        Stack fixtureStack = new Stack(app, "MskConnectFixtureStack", stackProps);

        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();

        SecurityGroup kafkaBrokerSg = SecurityGroup.Builder.create(fixtureStack, "TestKafkaBrokerSg")
                .vpc(vpc)
                .build();
        SecurityGroup kafkaConnectSg = SecurityGroup.Builder.create(fixtureStack, "TestKafkaConnectSg")
                .vpc(vpc)
                .build();

        Bucket clickLogBucket = Bucket.Builder.create(fixtureStack, "TestClickLogBucket")
                .bucketName("test-click-log-raw-bucket")
                .build();

        MskConnectStack stack = new MskConnectStack(
                app,
                "MskConnectStackTest",
                stackProps,
                vpc,
                kafkaBrokerSg,
                kafkaConnectSg,
                "holliverse-msk",
                "b-1.test.kafka.ap-northeast-2.amazonaws.com:9098",
                "arn:aws:kafka:ap-northeast-2:123456789012:cluster/holliverse-msk/test-cluster-id",
                "client-event-logs",
                clickLogBucket
        );

        Template template = Template.fromStack(stack);

        Map<String, Map<String, Object>> connectors = template.findResources("AWS::KafkaConnect::Connector");
        Map<String, Map<String, Object>> plugins = template.findResources("AWS::KafkaConnect::CustomPlugin");
        Map<String, Map<String, Object>> securityGroups = template.findResources("AWS::EC2::SecurityGroup");

        assertEquals(1, connectors.size());
        assertEquals(1, plugins.size());
        assertEquals(0, securityGroups.size());

        template.hasResourceProperties("AWS::KafkaConnect::Connector", Map.of(
                "ConnectorName", "click-log-s3-sink",
                "KafkaConnectVersion", "3.7.x"
        ));
        template.hasOutput("ClickLogConnectorName", Map.of());

        String templateJson = template.toJSON().toString();
        org.assertj.core.api.Assertions.assertThat(templateJson)
                .contains("client-event-logs")
                .contains("events/raw")
                .contains("'dt'=YYYY-MM-dd/'hour'=HH")
                .contains("3600000")
                .contains("Wallclock")
                .contains("kafka-cluster:CreateTopic")
                .contains("__amazon_msk_connect_")
                .contains("connect-*")
                .doesNotContain("TOKEN.")
                .contains("io.confluent.connect.s3.S3SinkConnector");
    }
}
