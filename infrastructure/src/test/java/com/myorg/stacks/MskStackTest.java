package com.myorg.stacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MskStackTest {

    @Test
    @DisplayName("Provisioned MSK 스택 기본 리소스가 생성되어야 한다.")
    void should_create_provisioned_msk_resources() {
        App app = new App();
        Stack fixtureStack = new Stack(app, "MskFixtureStack");

        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();

        SecurityGroup kafkaBrokerSg = SecurityGroup.Builder.create(fixtureStack, "TestKafkaBrokerSg")
                .vpc(vpc)
                .build();

        MskStack mskStack = new MskStack(
                app,
                "MskStackTest",
                StackProps.builder().build(),
                vpc,
                kafkaBrokerSg
        );

        Template template = Template.fromStack(mskStack);

        Map<String, Map<String, Object>> clusters = template.findResources("AWS::MSK::Cluster");
        Map<String, Map<String, Object>> customResources = template.findResources("Custom::AWS");

        assertEquals(1, clusters.size());
        assertEquals(1, customResources.size());

        template.hasResourceProperties("AWS::MSK::Cluster", Map.of(
                "ClusterName", "holliverse-msk",
                "KafkaVersion", "3.8.x",
                "NumberOfBrokerNodes", 4
        ));
        template.hasOutput("MskClusterArn", Map.of());
        template.hasOutput("MskBootstrapBrokersSaslIam", Map.of());

        String templateJson = template.toJSON().toString();
        org.assertj.core.api.Assertions.assertThat(templateJson)
                .contains("kafka.t3.small")
                .contains("TLS");
    }
}
