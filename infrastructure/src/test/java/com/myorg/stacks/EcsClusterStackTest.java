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
        SecurityGroup recommendationRealtimeSg = SecurityGroup.Builder.create(fixtureStack, "TestRecommendationRealtimeSg")
                .vpc(vpc)
                .build();
        SecurityGroup analysisServerSg = SecurityGroup.Builder.create(fixtureStack, "TestAnalysisServerSg")
                .vpc(vpc)
                .build();
        SecurityGroup logServerSg = SecurityGroup.Builder.create(fixtureStack, "TestLogServerSg")
                .vpc(vpc)
                .build();

        Repository adminWebRepo = Repository.Builder.create(fixtureStack, "TestAdminWebRepo")
                .repositoryName("test-admin-web")
                .build();
        Repository apiServerRepo = Repository.Builder.create(fixtureStack, "TestApiServerRepo")
                .repositoryName("test-api-server")
                .build();
        Repository logServerRepo = Repository.Builder.create(fixtureStack, "TestLogServerRepo")
                .repositoryName("test-log-server")
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
                recommendationRealtimeSg,
                analysisServerSg,
                logServerSg,
                adminWebRepo,
                apiServerRepo,
                logServerRepo,
                rds,
                dbSecret,
                "holliverse-msk",
                "arn:aws:kafka:ap-northeast-2:123456789012:cluster/holliverse-msk/test-cluster-id",
                "b-1.test.holliverse-msk.c2.kafka.ap-northeast-2.amazonaws.com:9098",
                3001,
                8080,
                8080,
                "latest",
                "admin-latest",
                "customer-latest"
        );
        Template template = Template.fromStack(ecsClusterStack);

        //when
        Map<String, Map<String, Object>> clusters = template.findResources("AWS::ECS::Cluster");
        Map<String, Map<String, Object>> logGroups = template.findResources("AWS::Logs::LogGroup");
        Map<String, Map<String, Object>> taskDefinitions = template.findResources("AWS::ECS::TaskDefinition");
        Map<String, Map<String, Object>> services = template.findResources("AWS::ECS::Service");
        Map<String, Map<String, Object>> namespaces = template.findResources("AWS::ServiceDiscovery::PrivateDnsNamespace");
        Map<String, Map<String, Object>> sdServices = template.findResources("AWS::ServiceDiscovery::Service");
        Map<String, Map<String, Object>> secrets = template.findResources("AWS::SecretsManager::Secret");

        //then
        assertEquals(1, clusters.size());
        assertEquals(1, logGroups.size());
        assertEquals(6, taskDefinitions.size());
        assertEquals(6, services.size());
        assertEquals(1, namespaces.size());
        assertEquals(4, sdServices.size());
        assertEquals(0, secrets.size());

        assertEquals(5, countServicesByExecOption(services, true));
        assertEquals(1, countServicesByExecOption(services, false));

        template.hasResourceProperties("AWS::Logs::LogGroup", Map.of(
                "LogGroupName", "/holliverse/ecs"
        ));
        template.hasResourceProperties("AWS::ECS::Service", Map.of(
                "DesiredCount", 1
        ));
        template.hasResourceProperties("AWS::ServiceDiscovery::PrivateDnsNamespace", Map.of(
                "Name", "example.internal"
        ));
        template.hasResourceProperties("AWS::ServiceDiscovery::Service", Map.of(
                "Name", "admin-api"
        ));
        template.hasResourceProperties("AWS::ServiceDiscovery::Service", Map.of(
                "Name", "customer-api"
        ));
        template.hasResourceProperties("AWS::ServiceDiscovery::Service", Map.of(
                "Name", "recommendation-realtime"
        ));
        template.hasResourceProperties("AWS::ServiceDiscovery::Service", Map.of(
                "Name", "analysis-server"
        ));
        template.hasResourceProperties("AWS::ECS::Service", Map.of(
                "ServiceName", "log-server"
        ));

        String templateJson = template.toJSON().toString();
        org.assertj.core.api.Assertions.assertThat(templateJson)
                .contains("KAFKA_CLICK_LOG_TOPIC")
                .contains("client-event-logs")
                .contains("KAFKA_GROUP_SPEED")
                .contains("click-log-consumer")
                .contains("KAFKA_TOPIC_CLIENT_EVENTS")
                .contains("error-logs")
                .contains("DB_URL")
                .contains("KAFKA_MAX_POLL_INTERVAL_MS")
                .contains("1800000")
                .contains("KAFKA_SESSION_TIMEOUT_MS")
                .contains("60000")
                .contains("KAFKA_HEARTBEAT_INTERVAL_MS")
                .contains("15000")
                .contains("OPENAI_CHAT_MODEL")
                .contains("gpt-4o-mini")
                .contains("OPENAI_EMBEDDING_MODEL")
                .contains("text-embedding-3-small")
                .contains("KAFKA_RECOMMENDATION_TOPIC")
                .contains("recommendation-topic")
                .contains("RECOMMEND_TOP_K")
                .contains("CACHE_TTL_DAYS")
                .contains("POSTGRES_DSN")
                .contains("OPENAI_API_KEY");

        String recommendationRealtimeTaskDefinitionJson = findTaskDefinitionByAppMode(taskDefinitions, "realtime").toString();
        org.assertj.core.api.Assertions.assertThat(recommendationRealtimeTaskDefinitionJson)
                .contains("KAFKA_BOOTSTRAP_SERVERS")
                .contains("MSK_BOOTSTRAP_SERVERS")
                .contains("b-1.test.holliverse-msk.c2.kafka.ap-northeast-2.amazonaws.com:9098")
                .contains("KAFKA_SECURITY_PROTOCOL")
                .contains("SASL_SSL")
                .contains("KAFKA_SASL_MECHANISM")
                .contains("OAUTHBEARER")
                .contains("KAFKA_RECOMMENDATION_TOPIC")
                .contains("recommendation-topic")
                .contains("/bin/sh")
                .contains("-lc")
                .contains("build_kafka_client_options")
                .contains("recommendation_service.py")
                .contains("docker-entrypoint.sh")
                .contains("OPENAI_CHAT_MODEL")
                .contains("gpt-4o-mini")
                .contains("OPENAI_EMBEDDING_MODEL")
                .contains("text-embedding-3-small")
                .contains("ADMIN_API_BASE_URL")
                .contains("http://admin-api.example.internal:8080")
                .contains("OPENAI_API_KEY");

        String analysisServerTaskDefinitionJson = findTaskDefinitionByAppMode(taskDefinitions, "analysis-server").toString();
        org.assertj.core.api.Assertions.assertThat(analysisServerTaskDefinitionJson)
                .contains("ADMIN_API_BASE_URL")
                .contains("http://admin-api.example.internal:8080")
                .contains("OPENAI_API_KEY");

        String customerApiTaskDefinitionJson = taskDefinitions.values().stream()
                .map(Object::toString)
                .filter(json -> json.contains("customer"))
                .findFirst()
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(customerApiTaskDefinitionJson)
                .contains("ADMIN_API_BASE_URL")
                .contains("http://admin-api.example.internal:8080");
    }

    @SuppressWarnings("unchecked")
    private static int countServicesByExecOption(Map<String, Map<String, Object>> services, boolean enabled) {
        return (int) services.values().stream()
                .map(resource -> (Map<String, Object>) resource.get("Properties"))
                .filter(properties -> Boolean.valueOf(enabled).equals(properties.get("EnableExecuteCommand")))
                .count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findTaskDefinitionByAppMode(
            Map<String, Map<String, Object>> taskDefinitions,
            String appMode
    ) {
        return taskDefinitions.values().stream()
                .map(resource -> (Map<String, Object>) resource.get("Properties"))
                .filter(properties -> hasAppMode(properties, appMode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TaskDefinition with APP_MODE=" + appMode + " not found"));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasAppMode(Map<String, Object> taskDefinitionProperties, String appMode) {
        List<Map<String, Object>> containerDefinitions =
                (List<Map<String, Object>>) taskDefinitionProperties.get("ContainerDefinitions");
        if (containerDefinitions == null || containerDefinitions.isEmpty()) {
            return false;
        }

        List<Map<String, Object>> environment =
                (List<Map<String, Object>>) containerDefinitions.get(0).get("Environment");
        if (environment == null) {
            return false;
        }

        return environment.stream().anyMatch(variable ->
                "APP_MODE".equals(variable.get("Name")) && appMode.equals(variable.get("Value")));
    }
}
