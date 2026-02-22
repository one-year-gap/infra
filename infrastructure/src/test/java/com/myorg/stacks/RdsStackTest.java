package com.myorg.stacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RdsStackTest {

    @Test
    @DisplayName("RDS Stack 기본 리소스가 생성되어야 한다.")
    void should_create_basic_rds_resources() {
        //given
        App app = new App();
        Stack fixtureStack = new Stack(app, "RdsFixtureStack");
        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();
        SecurityGroup customerApiSg = SecurityGroup.Builder.create(fixtureStack, "TestCustomerApiSg")
                .vpc(vpc)
                .build();
        SecurityGroup adminApiSg = SecurityGroup.Builder.create(fixtureStack, "TestAdminApiSg")
                .vpc(vpc)
                .build();
        SecurityGroup dbSg = SecurityGroup.Builder.create(fixtureStack, "TestDbSg")
                .vpc(vpc)
                .build();

        dbSg.addIngressRule(customerApiSg, Port.tcp(5432), "Customer API to DB");
        dbSg.addIngressRule(adminApiSg, Port.tcp(5432), "Admin API to DB");

        RdsStack rdsStack = new RdsStack(
                app,
                "RdsStackTest",
                StackProps.builder().build(),
                vpc,
                dbSg
        );
        Template template = Template.fromStack(rdsStack);

        //when
        Map<String, Map<String, Object>> dbInstances = template.findResources("AWS::RDS::DBInstance");
        Map<String, Map<String, Object>> dbParameterGroups = template.findResources("AWS::RDS::DBParameterGroup");
        Map<String, Map<String, Object>> secrets = template.findResources("AWS::SecretsManager::Secret");
        Map<String, Map<String, Object>> securityGroups = template.findResources("AWS::EC2::SecurityGroup");

        //then
        assertEquals(1, dbInstances.size());
        assertEquals(1, dbParameterGroups.size());
        assertEquals(1, secrets.size());
        assertEquals(0, securityGroups.size());

        template.hasResourceProperties("AWS::RDS::DBInstance", Map.of(
                "Engine", "postgres",
                "DBName", "holliverse"
        ));
        template.hasResourceProperties("AWS::SecretsManager::Secret", Map.of(
                "Name", "holliverse/rds/postgres"
        ));
        template.hasResourceProperties("AWS::RDS::DBParameterGroup", Map.of(
                "Family", "postgres16"
        ));
    }
}
