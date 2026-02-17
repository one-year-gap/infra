package com.myorg.stacks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53StackTest {
    private static final String TEST_DOMAIN = "example.com";
    private static final String TEST_APEX_IP = "203.0.113.10";

    @Test
    @DisplayName("Route53 Stack은 HostedZone, Apex ARecord, Output을 생성해야 한다.")
    void should_create_hosted_zone_apex_record_and_outputs() {
        //given
        App app = new App();
        Route53Stack stack = new Route53Stack(
                app,
                "Route53StackTest",
                StackProps.builder().build(),
                TEST_DOMAIN
        );
        Template template = Template.fromStack(stack);

        //when
        Map<String, Map<String, Object>> zones = template.findResources("AWS::Route53::HostedZone");
        Map<String, Map<String, Object>> records = template.findResources("AWS::Route53::RecordSet");

        //then
        assertEquals(1, zones.size());
        assertEquals(1, records.size());

        template.hasResourceProperties("AWS::Route53::HostedZone", Map.of(
                "Name", TEST_DOMAIN + "."
        ));

        template.hasResourceProperties("AWS::Route53::RecordSet", Map.of(
                "Type", "A",
                "Name", TEST_DOMAIN + ".",
                "ResourceRecords", java.util.List.of(TEST_APEX_IP)
        ));

        assertTrue(hasOutput(template, "HostedZoneNameServers"));
        assertTrue(hasOutput(template, "HostedZoneId"));
    }

    @SuppressWarnings("unchecked")
    private static boolean hasOutput(Template template, String outputName) {
        Map<String, Object> templateJson = (Map<String, Object>) template.toJSON();
        Map<String, Object> outputs = (Map<String, Object>) templateJson.get("Outputs");
        return outputs != null && outputs.containsKey(outputName);
    }
}
