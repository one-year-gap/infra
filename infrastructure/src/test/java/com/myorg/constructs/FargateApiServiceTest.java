package com.myorg.constructs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FargateApiServiceTest {

    @Test
    @DisplayName("Fargate API Service 기본 리소스가 생성되어야 한다.")
    void should_create_basic_api_service_resources() {
        //given
        App app = new App();
        Stack stack = new Stack(app, "FargateApiServiceTestStack");

        Vpc vpc = Vpc.Builder.create(stack, "TestVpc")
                .maxAzs(2)
                .build();
        Cluster cluster = Cluster.Builder.create(stack, "TestCluster")
                .vpc(vpc)
                .build();
        SecurityGroup serviceSg = SecurityGroup.Builder.create(stack, "TestApiServiceSg")
                .vpc(vpc)
                .build();
        Repository repository = Repository.Builder.create(stack, "TestApiRepo")
                .repositoryName("test-api-server")
                .build();
        LogGroup logGroup = LogGroup.Builder.create(stack, "TestLogGroup")
                .build();
        Secret dbSecret = Secret.Builder.create(stack, "TestDbSecret")
                .secretName("test/db/secret")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"holliverse\"}")
                        .generateStringKey("password")
                        .build())
                .build();

        new FargateApiService(
                stack,
                "TestApiService",
                cluster,
                repository,
                "latest",
                serviceSg,
                8080,
                logGroup,
                "admin-api",
                SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build(),
                1,
                true,
                "admin",
                "jdbc:postgresql://example.com:5432/holliverse",
                dbSecret
        );

        Template template = Template.fromStack(stack);

        //when
        Map<String, Map<String, Object>> taskDefinitions = template.findResources("AWS::ECS::TaskDefinition");
        Map<String, Map<String, Object>> services = template.findResources("AWS::ECS::Service");
        Map<String, Map<String, Object>> roles = template.findResources("AWS::IAM::Role");
        Map<String, Map<String, Object>> policies = template.findResources("AWS::IAM::Policy");
        Map<String, Map<String, Object>> secrets = template.findResources("AWS::SecretsManager::Secret");

        //then
        assertEquals(1, taskDefinitions.size());
        assertEquals(1, services.size());
        assertEquals(2, roles.size());
        assertTrue(policies.size() >= 1);
        assertEquals(1, secrets.size());

        template.hasResourceProperties("AWS::ECS::Service", Map.of(
                "DesiredCount", 1,
                "EnableExecuteCommand", true
        ));
        template.hasResourceProperties("AWS::SecretsManager::Secret", Map.of(
                "Name", "test/db/secret"
        ));
    }
}
