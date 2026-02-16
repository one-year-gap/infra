package com.myorg.constructs;

import com.myorg.props.FargateWebServiceProps;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FargateWebServiceTest {

    @Test
    @DisplayName("Fargate Web Service 기본 리소스가 생성되어야 한다.")
    void should_create_basic_web_service_resources() {
        //given
        App app = new App();
        Stack stack = new Stack(app, "FargateWebServiceTestStack");

        Vpc vpc = Vpc.Builder.create(stack, "TestVpc")
                .maxAzs(2)
                .build();
        Cluster cluster = Cluster.Builder.create(stack, "TestCluster")
                .vpc(vpc)
                .build();
        SecurityGroup serviceSg = SecurityGroup.Builder.create(stack, "TestWebServiceSg")
                .vpc(vpc)
                .build();
        Repository repository = Repository.Builder.create(stack, "TestWebRepo")
                .repositoryName("test-admin-web")
                .build();
        LogGroup logGroup = LogGroup.Builder.create(stack, "TestLogGroup")
                .build();

        FargateWebServiceProps props = new FargateWebServiceProps(
                stack,
                "TestWebService",
                cluster,
                repository,
                "latest",
                serviceSg,
                3000,
                logGroup,
                "admin-web",
                SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build(),
                1,
                false
        );

        new FargateWebService(props);

        Template template = Template.fromStack(stack);

        //when
        Map<String, Map<String, Object>> taskDefinitions = template.findResources("AWS::ECS::TaskDefinition");
        Map<String, Map<String, Object>> services = template.findResources("AWS::ECS::Service");
        Map<String, Map<String, Object>> roles = template.findResources("AWS::IAM::Role");

        //then
        assertEquals(1, taskDefinitions.size());
        assertEquals(1, services.size());
        assertEquals(2, roles.size());

        template.hasResourceProperties("AWS::ECS::Service", Map.of(
                "DesiredCount", 1,
                "EnableExecuteCommand", false
        ));
    }
}
