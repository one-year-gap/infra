package com.myorg.stacks;

import com.myorg.props.DnsProps;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.route53.ARecord;
import software.amazon.awscdk.services.route53.ARecordProps;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;
import software.constructs.Construct;

public class DnsStack extends Stack {
    public DnsStack(
            Construct scope, String id, StackProps props,
            DnsProps dnsProps
    ) {
        super(scope, id, props);

        // api.xxxx.com -> Customer ALB
        new ARecord(this, "ApiRecord", ARecordProps.builder()
                .zone(dnsProps.zone())
                .recordName("api")
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(dnsProps.customerAlb())))
                .build());

        //admin.xxxx.com -> Admin ALB
        new ARecord(this,"AdminRecord",ARecordProps.builder()
                .zone(dnsProps.zone())
                .recordName("admin")
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(dnsProps.adminAlb())))
                .build());
    }
}
