package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
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
    private final SecurityGroup customerAlbSg, customerApiSg, recommendationRealtimeSg, analysisServerSg, kafkaBrokerSg;
    private final SecurityGroup adminAlbSg, adminWebSg, adminApiSg, dbSg;
    private static final int MSK_IAM_PORT = 9098;

    /**
     * VPC와 서비스 보안 그룹 구성.
     */
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
        Integer recommendationRealtimePort = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_PORT));
        Integer analysisServerPort = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_PORT));


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

        // customer-api와 보안 경계를 분리하기 위해 별도 SG 사용
        this.recommendationRealtimeSg = SecurityGroup.Builder.create(this, "RecommendationRealtimeSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .description("Recommendation Realtime ECS Security Group")
                .build();

        this.analysisServerSg = SecurityGroup.Builder.create(this, "AnalysisServerSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .description("Analysis Server ECS Security Group")
                .build();

        // Kafka broker SG는 client SG에서만 IAM/TLS 포트를 열어 private 통신만 허용
        this.kafkaBrokerSg = SecurityGroup.Builder.create(this, "KafkaBrokerSg")
                .vpc(vpc)
                .allowAllOutbound(false)
                .disableInlineRules(true)
                .description("MSK Broker Security Group")
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
        customerApiSg.addEgressRule(
                Peer.securityGroupId(kafkaBrokerSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "To MSK IAM only"
        );

        dbSg.addIngressRule(
                Peer.securityGroupId(customerApiSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "Customer API to DB"
        );
        kafkaBrokerSg.addIngressRule(
                Peer.securityGroupId(customerApiSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "From Customer API only"
        );

        /*
         * =================================================================
         *                   Recommendation Realtime Rules
         * =================================================================
         */
        recommendationRealtimeSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.HTTPS, "HTTPS");
        recommendationRealtimeSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_TCP, "DNS");
        recommendationRealtimeSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_UDP, "DNS(UDP)");
        recommendationRealtimeSg.addEgressRule(
                Peer.securityGroupId(kafkaBrokerSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "To MSK IAM only"
        );

        kafkaBrokerSg.addIngressRule(
                Peer.securityGroupId(recommendationRealtimeSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "From Recommendation Realtime only"
        );
        recommendationRealtimeSg.addIngressRule(
                Peer.securityGroupId(adminApiSg.getSecurityGroupId()),
                Port.tcp(recommendationRealtimePort),
                "From Admin API only"
        );
        recommendationRealtimeSg.addEgressRule(
                Peer.securityGroupId(adminApiSg.getSecurityGroupId()),
                Port.tcp(adminServerPort),
                "To Admin API only"
        );
        adminApiSg.addIngressRule(
                Peer.securityGroupId(recommendationRealtimeSg.getSecurityGroupId()),
                Port.tcp(adminServerPort),
                "From Recommendation Realtime only"
        );

        /*
         * =================================================================
         *                   Analysis Server Rules
         * =================================================================
         */
        analysisServerSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.HTTPS, "HTTPS");
        analysisServerSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_TCP, "DNS");
        analysisServerSg.addEgressRule(Peer.anyIpv4(), NetworkConstants.DNS_UDP, "DNS(UDP)");
        analysisServerSg.addEgressRule(
                Peer.securityGroupId(kafkaBrokerSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "To MSK IAM only"
        );
        analysisServerSg.addEgressRule(
                Peer.securityGroupId(dbSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "To DB only"
        );

        kafkaBrokerSg.addIngressRule(
                Peer.securityGroupId(analysisServerSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "From Analysis Server only"
        );
        dbSg.addIngressRule(
                Peer.securityGroupId(analysisServerSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "Analysis Server to DB"
        );
        analysisServerSg.addIngressRule(
                Peer.securityGroupId(adminApiSg.getSecurityGroupId()),
                Port.tcp(analysisServerPort),
                "From readiness probe/admin API only"
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
        adminApiSg.addEgressRule(
                Peer.securityGroupId(kafkaBrokerSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "To MSK IAM only"
        );
        adminApiSg.addEgressRule(
                Peer.securityGroupId(recommendationRealtimeSg.getSecurityGroupId()),
                Port.tcp(recommendationRealtimePort),
                "To Recommendation Realtime only"
        );
        adminApiSg.addEgressRule(
                Peer.securityGroupId(analysisServerSg.getSecurityGroupId()),
                Port.tcp(analysisServerPort),
                "To Analysis Server only"
        );

        dbSg.addIngressRule(
                Peer.securityGroupId(adminApiSg.getSecurityGroupId()),
                NetworkConstants.POSTGRES,
                "Admin API to DB"
        );
        kafkaBrokerSg.addIngressRule(
                Peer.securityGroupId(adminApiSg.getSecurityGroupId()),
                Port.tcp(MSK_IAM_PORT),
                "From Admin API only"
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

    public SecurityGroup getRecommendationRealtimeSg() {
        return recommendationRealtimeSg;
    }

    public SecurityGroup getAnalysisServerSg() {
        return analysisServerSg;
    }

    public SecurityGroup getKafkaBrokerSg() {
        return kafkaBrokerSg;
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
