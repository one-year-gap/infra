package com.myorg.stacks;

import com.myorg.config.NetworkStackConfig;
import com.myorg.constants.NetworkConstants;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;

public class NetworkStack extends Stack {
    private final Vpc vpc;
    private final SecurityGroup customerAlbSg, customerApiSg;
    private final SecurityGroup adminAlbSg, adminWebSg, adminApiSg, dbSg;

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
                .maxAzs(NetworkConstants.MAX_AZ)//가용영역
                .natGateways(NetworkConstants.NAT_GATEWAYS)
                .subnetConfiguration(Arrays.asList(
                        SubnetConfiguration
                                .builder()
                                .name(NetworkConstants.SUBNET_PUBLIC)
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(NetworkConstants.CIDR_MASK)
                                .build(),
                        SubnetConfiguration
                                .builder()
                                .name(NetworkConstants.SUBNET_PRIVATE)
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(NetworkConstants.CIDR_MASK)
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

        this.dbSg = SecurityGroup.Builder.create(this, "HolliverseDbSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .description("Database SecurityGroup: allow 5432 from API Server")
                .build();


        /*
         * =================================================================
         *                   Customer Rules
         * =================================================================
         */
        customerAlbSg.addIngressRule(Peer.anyIpv4(), NetworkConstants.HTTP, "HTTP");
        customerAlbSg.addIngressRule(Peer.anyIpv4(), NetworkConstants.HTTPS, "HTTPS");

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

        customerApiSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.HTTPS, "HTTPS out");
        customerApiSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_TCP, "DNS");
        customerApiSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_UDP, "DNS(UDP)");
        customerApiSg.addEgressRule(
                Peer.securityGroupId(dbSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "To DB only"
        );

        dbSg.addIngressRule(
                Peer.securityGroupId(customerApiSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "Customer API to DB"
        );


        /*
         * =================================================================
         *                         Admin Rules
         * =================================================================
         */
        allowedIpList.forEach(ip -> {
            adminAlbSg.addIngressRule(Peer.ipv4(ip), NetworkConstants.HTTP, "Admin HTTP from allowed IP");
            adminAlbSg.addIngressRule(Peer.ipv4(ip), NetworkConstants.HTTPS, "Admin HTTPS from allowed IP");
        });


        /*
         * =================================================================
         *                   Admin Web SecurityGroup
         * =================================================================
         */
        adminWebSg.addIngressRule(
                Peer.securityGroupId(adminAlbSg.getSecurityGroupId()),
                Port.tcp(adminWebPort), "From Admin ALB");
        adminWebSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.HTTPS, "HTTPS");
        adminWebSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_TCP, "DNS");
        adminWebSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_UDP, "DNS(UDP)");

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


        adminApiSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.HTTPS, "HTTPS");
        adminApiSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_TCP, "DNS");
        adminApiSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_UDP, "DNS(UDP)");
        adminApiSg.addEgressRule(
                Peer.securityGroupId(dbSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "To DB only"
        );

        dbSg.addIngressRule(
                Peer.securityGroupId(adminApiSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "Admin API to DB"
        );
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

    public SecurityGroup getDbSg() {
        return dbSg;
    }
}
