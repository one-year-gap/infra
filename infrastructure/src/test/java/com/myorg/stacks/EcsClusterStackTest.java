package com.myorg.stacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EcsClusterStackTest {

    @Test
    @DisplayName("ECS Cluster Stack 기본 리소스가 생성되어야 한다.")
    void should_create_basic_ecs_cluster_resources() {
        //given
        App app = new App();
        Stack fixtureStack = new Stack(app, "EcsClusterFixtureStack");

        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();

        SecurityGroup adminWebSg = SecurityGroup.Builder.create(fixtureStack, "TestAdminWebSg")
                .vpc(vpc)
                .build();
        SecurityGroup adminApiSg = SecurityGroup.Builder.create(fixtureStack, "TestAdminApiSg")
                .vpc(vpc)
                .build();
        SecurityGroup customerApiSg = SecurityGroup.Builder.create(fixtureStack, "TestCustomerApiSg")
                .vpc(vpc)
                .build();

        Repository adminWebRepo = Repository.Builder.create(fixtureStack, "TestAdminWebRepo")
                .repositoryName("test-admin-web")
                .build();
        Repository apiServerRepo = Repository.Builder.create(fixtureStack, "TestApiServerRepo")
                .repositoryName("test-api-server")
                .build();

        Secret dbSecret = Secret.Builder.create(fixtureStack, "TestDbSecret")
                .secretName("test/ecs/db")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"username\":\"holliverse\"}")
                        .generateStringKey("password")
                        .build())
                .build();

        SecurityGroup dbSg = SecurityGroup.Builder.create(fixtureStack, "TestDbSg")
                .vpc(vpc)
                .build();

        DatabaseInstance rds = DatabaseInstance.Builder.create(fixtureStack, "TestRds")
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_16)
                        .build()))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build())
                .securityGroups(List.of(dbSg))
                .credentials(Credentials.fromSecret(dbSecret))
                .databaseName("holliverse")
                .port(5432)
                .instanceType(InstanceType.of(InstanceClass.T4G, InstanceSize.MICRO))
                .allocatedStorage(20)
                .build();

        EcsClusterStack ecsClusterStack = new EcsClusterStack(
                app,
                "EcsClusterStackTest",
                StackProps.builder().build(),
                vpc,
                adminWebSg,
                adminApiSg,
                customerApiSg,
                adminWebRepo,
                apiServerRepo,
                rds,
                dbSecret,
                3001,
                8080,
                8080,
                "latest",
                "latest"
        );
        Template template = Template.fromStack(ecsClusterStack);

        //when
        Map<String, Map<String, Object>> clusters = template.findResources("AWS::ECS::Cluster");
        Map<String, Map<String, Object>> logGroups = template.findResources("AWS::Logs::LogGroup");
        Map<String, Map<String, Object>> taskDefinitions = template.findResources("AWS::ECS::TaskDefinition");
        Map<String, Map<String, Object>> services = template.findResources("AWS::ECS::Service");

        //then
        assertEquals(1, clusters.size());
        assertEquals(1, logGroups.size());
        assertEquals(3, taskDefinitions.size());
        assertEquals(3, services.size());

        assertEquals(2, countServicesByExecOption(services, true));
        assertEquals(1, countServicesByExecOption(services, false));

        template.hasResourceProperties("AWS::Logs::LogGroup", Map.of(
                "LogGroupName", "/holliverse/ecs"
        ));
        template.hasResourceProperties("AWS::ECS::Service", Map.of(
                "DesiredCount", 1
        ));
    }

    @SuppressWarnings("unchecked")
    private static int countServicesByExecOption(Map<String, Map<String, Object>> services, boolean enabled) {
        return (int) services.values().stream()
                .map(resource -> (Map<String, Object>) resource.get("Properties"))
                .filter(properties -> Boolean.valueOf(enabled).equals(properties.get("EnableExecuteCommand")))
                .count();
    }
}
