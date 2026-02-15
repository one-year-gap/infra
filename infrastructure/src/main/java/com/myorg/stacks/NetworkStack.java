package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.NetworkStackConfig;
import com.myorg.config.PortConfig;
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
        //Admin Web Server
        Integer adminServerPort = config.adminServerPort();
        //Admin Web
        Integer adminWebPort = config.adminWebPort();

        //Customer Web Server
        Integer customerServerPort = config.customerServerPort();
        //Customer Web
        Integer customerWebPort = config.customerWebPort();


        /*
         * =================================================================
         *                              VPC
         * =================================================================
         */
        this.vpc = Vpc.Builder.create(this, "AppVpc")
                .maxAzs(2)//가용영역
                .natGateways(1)
                .subnetConfiguration(Arrays.asList(
                        SubnetConfiguration.builder()
                                .name("Public").subnetType(SubnetType.PUBLIC).cidrMask(24).build(),
                        SubnetConfiguration.builder().name("Private").subnetType(SubnetType.PRIVATE_WITH_EGRESS).cidrMask(24).build()
                )).build();

        /*
         * =================================================================
         *                   Customer ALB Security Group
         * =================================================================
         */
        this.customerAlbSg = SecurityGroup.Builder.create(this, "CustomerAlbSg")
                .vpc(vpc).allowAllOutbound(false).build();

        customerAlbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "HTTP");
        customerAlbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");
        customerAlbSg.addIngressRule(Peer.anyIpv4(), Port.tcp(customerServerPort), "To Ecs");


        /*
         * =================================================================
         *                     Customer API Security Group
         * =================================================================
         */
        this.customerApiSg = SecurityGroup.Builder.create(this, "CustomerApiSg")
                .vpc(vpc).allowAllOutbound(false).build();

        customerApiSg.addIngressRule(
                Peer.securityGroupId(customerAlbSg.getSecurityGroupId()), Port.tcp(8080), "From customer ALB only"
        );
        customerApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS out");
        customerApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(53), "DNS");

        /*
         * =================================================================
         *                         Admin ALB SG
         * =================================================================
         */
        this.adminAlbSg = SecurityGroup.Builder.create(this, "AdminAlbSg")
                .vpc(vpc).allowAllOutbound(false).build();

        allowedIpList.forEach(ip ->
                adminAlbSg.addIngressRule(Peer.ipv4(ip), Port.tcp(80)));
        adminAlbSg.addEgressRule(Peer.anyIpv4(), Port.tcp(adminServerPort), "To Admin ECS");
        adminAlbSg.addEgressRule(Peer.anyIpv4(), Port.tcp(adminWebPort), "To Admin Web");

        /*
         * =================================================================
         *                   Admin Web SecurityGroup
         * =================================================================
         */
        this.adminWebSg = SecurityGroup.Builder.create(this, "AdminWebSg")
                .vpc(vpc).allowAllOutbound(false).build();
        adminWebSg.addIngressRule(
                Peer.securityGroupId(adminAlbSg.getSecurityGroupId()),
                Port.tcp(adminWebPort), "From Admin ALB");
        adminWebSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");
        adminWebSg.addEgressRule(Peer.anyIpv4(), Port.tcp(53), "DNS");

        //Admin IP로만 outbound
        adminWebSg.addEgressRule(Peer.securityGroupId(
                        adminAlbSg.getSecurityGroupId()),
                Port.tcp(adminServerPort), "To Admin API only"
        );

        /*
         * =================================================================
         *                      Admin API SecurityGroup
         * =================================================================
         */
        this.adminApiSg = SecurityGroup.Builder.create(this, "AdminApiSg")
                .vpc(vpc).allowAllOutbound(false).build();
        adminApiSg.addIngressRule(
                Peer.securityGroupId(adminAlbSg.getSecurityGroupId()),
                Port.tcp(adminServerPort), "From Admin ALB only"
        );
        adminApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");
        adminApiSg.addEgressRule(Peer.anyIpv4(), Port.tcp(53), "DNS");
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
