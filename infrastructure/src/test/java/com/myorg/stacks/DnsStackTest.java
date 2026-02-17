package com.myorg.stacks;

import com.myorg.props.DnsProps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.route53.PublicHostedZone;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsStackTest {
    private static final String TEST_DOMAIN = "example.com";

    @Test
    @DisplayName("DNS Stack은 api/admin/api-admin A 레코드를 생성한다.")
    void should_create_dns_a_records() {
        //given
        App app = new App();
        Stack fixtureStack = new Stack(app, "DnsFixtureStack");

        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();

        ApplicationLoadBalancer customerAlb = ApplicationLoadBalancer.Builder.create(fixtureStack, "CustomerAlb")
                .vpc(vpc)
                .internetFacing(true)
                .build();

        ApplicationLoadBalancer adminAlb = ApplicationLoadBalancer.Builder.create(fixtureStack, "AdminAlb")
                .vpc(vpc)
                .internetFacing(true)
                .build();

        PublicHostedZone zone = PublicHostedZone.Builder.create(fixtureStack, "TestHostedZone")
                .zoneName(TEST_DOMAIN)
                .build();

        DnsStack dnsStack = new DnsStack(
                app,
                "DnsStackTest",
                StackProps.builder().build(),
                new DnsProps(zone, customerAlb, adminAlb)
        );
        Template template = Template.fromStack(dnsStack);

        //when
        Map<String, Map<String, Object>> recordSets = template.findResources("AWS::Route53::RecordSet");

        //then
        assertEquals(3, recordSets.size());
        assertTrue(hasARecord(recordSets, "api." + TEST_DOMAIN + "."));
        assertTrue(hasARecord(recordSets, "admin." + TEST_DOMAIN + "."));
        assertTrue(hasARecord(recordSets, "api-admin." + TEST_DOMAIN + "."));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasARecord(Map<String, Map<String, Object>> recordSets, String expectedName) {
        return recordSets.values().stream().anyMatch(resource -> {
            Map<String, Object> properties = (Map<String, Object>) resource.get("Properties");
            return "A".equals(properties.get("Type")) && expectedName.equals(properties.get("Name"));
        });
    }
}
