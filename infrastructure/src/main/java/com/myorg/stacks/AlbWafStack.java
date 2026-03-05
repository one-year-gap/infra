package com.myorg.stacks;

import com.myorg.config.WafConfig;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.IApplicationLoadBalancer;
import software.amazon.awscdk.services.wafv2.CfnWebACL;
import software.amazon.awscdk.services.wafv2.CfnWebACLAssociation;
import software.constructs.Construct;

import java.util.List;

/**
 * ALB WAF 스택.
 * - Web ACL 생성
 * - Customer/Admin ALB에 Web ACL 연결
 *
 * 규칙:
 * - 국가 기반 차단
 * - IP rate limit 차단
 * - User-Agent 패턴 차단
 */
public class AlbWafStack extends Stack {
    private static final String WAF_SCOPE_REGIONAL = "REGIONAL";

    public AlbWafStack(
            Construct scope,
            String id,
            StackProps props,
            IApplicationLoadBalancer customerAlb,
            IApplicationLoadBalancer adminAlb,
            WafConfig config
    ) {
        super(scope, id, props);

        if (!config.enabled()) {
            return;
        }
        if (!config.attachToCustomerAlb() && !config.attachToAdminAlb()) {
            return;
        }
        if (config.blockedCountries().isEmpty()) {
            throw new IllegalStateException("ALB_WAF_BLOCKED_COUNTRIES must not be empty when ALB_WAF_ENABLED=true");
        }

        CfnWebACL webAcl = CfnWebACL.Builder.create(this, "AlbWebAcl")
                .name("holliverse-alb-web-acl")
                .scope(WAF_SCOPE_REGIONAL)
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .sampledRequestsEnabled(true)
                        .cloudWatchMetricsEnabled(true)
                        .metricName("ALB_WEB_ACL")
                        .build())
                .rules(buildRules(config))
                .build();

        if (config.attachToCustomerAlb()) {
            CfnWebACLAssociation.Builder.create(this, "CustomerAlbWebAclAssociation")
                    .resourceArn(customerAlb.getLoadBalancerArn())
                    .webAclArn(webAcl.getAttrArn())
                    .build();
        }

        if (config.attachToAdminAlb()) {
            CfnWebACLAssociation.Builder.create(this, "AdminAlbWebAclAssociation")
                    .resourceArn(adminAlb.getLoadBalancerArn())
                    .webAclArn(webAcl.getAttrArn())
                    .build();
        }

        CfnOutput.Builder.create(this, "AlbWebAclArn")
                .value(webAcl.getAttrArn())
                .description("ALB WAF Web ACL ARN")
                .build();
    }

    private List<CfnWebACL.RuleProperty> buildRules(WafConfig config) {
        return List.of(
                countryBlockRule(config.blockedCountries()),
                rateLimitRule(config.maxRequestsPerFiveMinutesPerIp())
        );
    }

    private CfnWebACL.RuleProperty countryBlockRule(List<String> blockedCountries) {
        return CfnWebACL.RuleProperty.builder()
                .name("COUNTRY_BLOCK_CHINA")
                .priority(0)
                .action(CfnWebACL.RuleActionProperty.builder()
                        .block(CfnWebACL.BlockActionProperty.builder().build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .sampledRequestsEnabled(true)
                        .cloudWatchMetricsEnabled(true)
                        .metricName("COUNTRY_BLOCK_CHINA")
                        .build())
                .statement(CfnWebACL.StatementProperty.builder()
                        .geoMatchStatement(CfnWebACL.GeoMatchStatementProperty.builder()
                                .countryCodes(blockedCountries)
                                .build())
                        .build())
                .build();
    }

    private CfnWebACL.RuleProperty rateLimitRule(long limitPerFiveMinutes) {
        return CfnWebACL.RuleProperty.builder()
                .name("MAX_REQUEST_1000")
                .priority(1)
                .action(CfnWebACL.RuleActionProperty.builder()
                        .block(CfnWebACL.BlockActionProperty.builder().build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .sampledRequestsEnabled(true)
                        .cloudWatchMetricsEnabled(true)
                        .metricName("MAX_REQUEST_1000")
                        .build())
                .statement(CfnWebACL.StatementProperty.builder()
                        .rateBasedStatement(CfnWebACL.RateBasedStatementProperty.builder()
                                .limit(limitPerFiveMinutes)
                                .aggregateKeyType("IP")
                                .build())
                        .build())
                .build();
    }
}
