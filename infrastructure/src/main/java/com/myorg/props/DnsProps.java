package com.myorg.props;

import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.route53.IHostedZone;

public record DnsProps(
        IHostedZone zone,
        ApplicationLoadBalancer customerAlb,
        ApplicationLoadBalancer adminAlb
) {
}
