package com.myorg;

import com.myorg.config.AppConfig;
import com.myorg.stacks.Route53Stack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class InfrastructureApp {
    private static final String ROUTE53_STACK_ID = "Route53Stack";

    public static void main(String[] args) {
        App app = new App();

        String domainName = resolveDomainName(app);
        String region = AppConfig.getRegion();

        StackProps stackProps = StackProps.builder()
                .env(Environment.builder().region(region).build())
                .build();

        new Route53Stack(app, ROUTE53_STACK_ID, stackProps, domainName);

        app.synth();
    }

    private static String resolveDomainName(App app) {
        Object fromContext = app.getNode().tryGetContext("domainName");
        if (fromContext != null) {
            String domain = String.valueOf(fromContext).trim();
            if (!domain.isBlank()) {
                return domain;
            }
        }

        return AppConfig.getDomainName();
    }
}
