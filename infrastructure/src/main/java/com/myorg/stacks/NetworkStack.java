package com.myorg.stacks;

import com.myorg.config.NetworkStackConfig;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

public class NetworkStack extends Stack {
    private final Vpc vpc;
    private final SecurityGroup customerAlbSg, customerApiSg;
    private final SecurityGroup adminAlbSg, adminWebSg, adminApiSg;

    public NetworkStack(Construct scope, String id, StackProps props, NetworkStackConfig config) {
        super(scope, id, props);

        //Admin Allowed IP List
        List<String> allowedIpList = config.adminAllowedCidrs();
        //Admin Web(ECS) Server
        Integer adminServerPort = config.adminServerPort();
        //Admin Web(ECS)
        Integer adminWebPort = config.adminWebPort();

        //Customer Web(ECS) Server
        Integer customerServerPort = config.customerServerPort();


        /*
         * =================================================================
         *                              VPC
         * =================================================================
         */
        this.vpc = Vpc.Builder.create(this, "AppVpc")
                .maxAzs(2)//가용영역
                .natGateways(1)
                .subnetConfiguration(Arrays.asList(
                        SubnetConfiguration
                                .builder()
                                .name("Public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration
                                .builder()
                                .name("Private")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(24)
                                .build()
                )).build();

        /*
         * =================================================================
         *                         Security Group
         * =================================================================
         */

        this.customerAlbSg = SecurityGroup.Builder.create(this, "CustomerAlbSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .description("Customer Application Load Balancer Security Group")
                .build();

        this.customerApiSg = SecurityGroup.Builder.create(this, "CustomerApiSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .description("Customer API Server Security Group")
                .build();

        this.adminAlbSg = SecurityGroup.Builder.create(this, "AdminAlbSg")
                .vpc(vpc)
                .description("Admin Application Load Balancer Security Group")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();

        this.adminWebSg = SecurityGroup.Builder.create(this, "AdminWebSg")
                .vpc(vpc)
                .description("Admin Web(ECS) Security Group")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();

        this.adminApiSg = SecurityGroup.Builder.create(this, "AdminApiSg")
                .vpc(vpc)
                .description("Admin API Server(ECS) Security Group")
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .build();


        /*
         * =================================================================
         *                   Customer Rules
         * =================================================================
         */
        customerAlbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "HTTP");
        customerAlbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");

        //ALB -> Customer API only
        customerAlbSg.addEgressRule(
                Peer.securityGroupId(customerApiSg.getSecurityGroupId()),
                Port.tcp(customerServerPort),
                "To Customer API ECS only"
        );
        //Customer API -> ALB only
        customerApiSg.addIngressRule(Peer.securityGroupId(
                        customerAlbSg.getSecurityGroupId()),
                Port.tcp(customerServerPort),
                "From customer ALB only"
        );

        customerApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS out");
        customerApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(53), "DNS");
        customerApiSg.addEgressRule(Peer.anyIpv4(), Port.udp(53), "DNS(UDP)");


        /*
         * =================================================================
         *                         Admin Rules
         * =================================================================
         */
        allowedIpList.forEach(ip -> {
            adminAlbSg.addIngressRule(Peer.ipv4(ip), Port.tcp(80), "Admin HTTP from allowed IP");
            adminAlbSg.addIngressRule(Peer.ipv4(ip), Port.tcp(443), "Admin HTTPS from allowed IP");
        });


        /*
         * =================================================================
         *                   Admin Web SecurityGroup
         * =================================================================
         */
        adminWebSg.addIngressRule(
                Peer.securityGroupId(adminAlbSg.getSecurityGroupId()),
                Port.tcp(adminWebPort), "From Admin ALB");
        adminWebSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");
        adminWebSg.addEgressRule(Peer.anyIpv4(), Port.tcp(53), "DNS");
        adminWebSg.addEgressRule(Peer.anyIpv4(), Port.udp(53), "DNS(UDP)");

        //ALB -> Admin Web only
        adminAlbSg.addEgressRule(
                Peer.securityGroupId(adminWebSg.getSecurityGroupId()),
                Port.tcp(adminWebPort),
                "To Admin Web ECS only"
        );

        //Admin Web -> Admin ALB
        adminWebSg.addEgressRule(Peer.securityGroupId(
                        adminApiSg.getSecurityGroupId()),
                Port.tcp(adminServerPort), "To Admin API only"
        );

        //Admin Web -> Admin API
        adminApiSg.addIngressRule(
                Peer.securityGroupId(adminWebSg.getSecurityGroupId()),
                Port.tcp(adminServerPort),
                "From Admin Web only (direct)"
        );

        //Admin ALB -> Admin API
        adminApiSg.addIngressRule(
                Peer.securityGroupId(adminAlbSg.getSecurityGroupId()),
                Port.tcp(adminServerPort),
                "From Admin ALB (direct)"
        );


        adminApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");
        adminApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(53), "DNS");
        adminApiSg.addEgressRule(Peer.anyIpv4(), Port.udp(53), "DNS(UDP)");
    }


    public Vpc getVpc() {
        return vpc;
    }

    public SecurityGroup getCustomerAlbSg() {
        return customerAlbSg;
    }

    public SecurityGroup getCustomerApiSg() {
        return customerApiSg;
    }

    public SecurityGroup getAdminAlbSg() {
        return adminAlbSg;
    }

    public SecurityGroup getAdminWebSg() {
        return adminWebSg;
    }

    public SecurityGroup getAdminApiSg() {
        return adminApiSg;
    }
}
