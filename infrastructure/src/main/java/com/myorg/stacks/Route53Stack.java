package com.myorg.stacks;

import com.myorg.config.AppConfig;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.route53.*;
import software.constructs.Construct;

public class Route53Stack extends Stack {
    private final PublicHostedZone zone;

    public Route53Stack(Construct scope, String id, StackProps props,String domainName){
        super(scope,id,props);

        this.zone = PublicHostedZone.Builder.create(this,"HolliverseZone")
                .zoneName(domainName)
                .build();

        /**
         * Vercel - Customer Web
         */
        new ARecord(this,"VercelApexRecord",
                ARecordProps.builder()
                        .zone(zone)
                        .recordName("")
                        .target(RecordTarget.fromIpAddresses(AppConfig.getVercelIp()))
                        .build());

        CfnOutput.Builder.create(this,"HostedZoneNameServers")
                .value(Fn.join(", ",zone.getHostedZoneNameServers()))
                .build();

        CfnOutput.Builder.create(this,"HostedZoneId")
                .value(zone.getHostedZoneId())
                .build();

    }

    public IHostedZone getZone(){
        return zone;
    }
}
