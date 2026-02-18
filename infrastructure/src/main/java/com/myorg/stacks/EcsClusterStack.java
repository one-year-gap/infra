package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.constructs.FargateApiService;
import com.myorg.constructs.FargateWebService;
import com.myorg.props.FargateApiServiceProps;
import com.myorg.props.FargateWebServiceProps;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespace;
import software.constructs.Construct;

public class EcsClusterStack extends Stack {
    private final Cluster cluster;
    private final LogGroup ecsLogGroup;

    private final FargateApiService customerApiService;
    private final FargateApiService adminApiService;

    private final FargateWebService adminWebService;

    /**
     * ID 상수
     */
    private static final String CLUSTER_ID = "HolliverseCluster";
    private static final String LOG_GROUP_ID = "EcsLogGroup";

    private static final String ADMIN_WEB_ID = "AdminWeb";
    private static final String ADMIN_WEB_LOG_STREAM_PREFIX = "admin-web";

    private static final String ADMIN_API_ID = "AdminApi";
    private static final String ADMIN_API_LOG_STREAM_PREFIX = "admin-api";

    private static final String CUSTOMER_API_ID = "CustomerApi";
    private static final String CUSTOMER_API_LOG_STREAM_PREFIX = "customer-api";

    /**
     * 서비스 상수
     */
    private static final int DESIRED_COUNT = 1;
    private static final String PROFILE_ADMIN = "admin";
    private static final String PROFILE_CUSTOMER = "customer";
    private static final String DOMAIN_NAME_SPACE = "ServiceNs";
    private static final String ADMIN_CLOUD_MAP_NAME="admin-api";

    /**
     * DB 상수
     */
    private static final String DATABASE_NAME = "holliverse";

    public EcsClusterStack(
            Construct scope,
            String id,
            StackProps props,

            //NetworkStack에서 가져옴
            Vpc vpc,
            SecurityGroup adminWebSg,
            SecurityGroup adminApiSg,
            SecurityGroup customerApiSg,

            //EcrStack에서 가져옴
            Repository adminWebRepo,
            Repository apiServerRepo,

            //RdsStack에서 내려오는 것
            DatabaseInstance rds,
            Secret dbSecret,

            //포트/태그
            int adminWebPort,
            int adminApiPort,
            int customerApiPort,
            String adminWebImageTag,
            String adminApiImageTag,
            String customerApiImageTag
    ) {
        super(scope, id, props);
        /**
         * 1) 공통 Subnet
         */
        SubnetSelection privateSubnets = SubnetSelection.builder()
                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                .build();

        PrivateDnsNamespace serviceNs = PrivateDnsNamespace.Builder.create(this, DOMAIN_NAME_SPACE)
                .vpc(vpc)
                .name(AppConfig.getInternalDomainName())
                .build();

        /**
         * 2) CloudWatch LogGroup
         */
        this.ecsLogGroup = LogGroup.Builder.create(this, LOG_GROUP_ID)
                .logGroupName("/holliverse/ecs")
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        /**
         * 3) ECS Cluster
         */
        this.cluster = Cluster.Builder.create(this, CLUSTER_ID)
                .vpc(vpc)
                .containerInsights(true)
                .build();

        /**
         * 4) Database URL 구성
         */
        String dbUrl = String.format(
                "jdbc:postgresql://%s:%s/%s",
                rds.getDbInstanceEndpointAddress(),
                rds.getDbInstanceEndpointPort(),
                DATABASE_NAME
        );

        /**
         * Props Setup
         */
        FargateWebServiceProps adminWebServiceProps = new FargateWebServiceProps(
                this,
                ADMIN_WEB_ID,
                cluster,
                adminWebRepo,
                adminWebImageTag,
                adminWebSg,
                adminWebPort,
                ecsLogGroup,
                ADMIN_WEB_LOG_STREAM_PREFIX,
                privateSubnets,
                DESIRED_COUNT,
                false
        );
        FargateApiServiceProps adminApiServiceProps = new FargateApiServiceProps(
                this,
                ADMIN_API_ID,
                cluster,
                apiServerRepo,
                adminApiImageTag,
                adminApiSg,
                adminApiPort,
                ecsLogGroup,
                ADMIN_API_LOG_STREAM_PREFIX,
                privateSubnets,
                DESIRED_COUNT,
                true,
                PROFILE_ADMIN,
                dbUrl,
                dbSecret,
                serviceNs,
                ADMIN_CLOUD_MAP_NAME
        );

        FargateApiServiceProps customerApiServiceProps = new FargateApiServiceProps(
                this,
                CUSTOMER_API_ID,
                cluster,
                apiServerRepo,
                customerApiImageTag,
                customerApiSg,
                customerApiPort,
                ecsLogGroup,
                CUSTOMER_API_LOG_STREAM_PREFIX,
                privateSubnets,
                DESIRED_COUNT,
                true,
                PROFILE_CUSTOMER,
                dbUrl,
                dbSecret,
                null,
                null
        );

        /**
         * 5) Admin Web - Next.js
         */
        this.adminWebService = new FargateWebService(adminWebServiceProps);

        /**
         * 6) Admin API - Spring boot
         */
        this.adminApiService = new FargateApiService(adminApiServiceProps);

        /**
         * 7) customer API - Springboot
         */
        this.customerApiService = new FargateApiService(customerApiServiceProps);
    }

    public Cluster getCluster() {
        return cluster;
    }

    public LogGroup getEcsLogGroup() {
        return ecsLogGroup;
    }

    public FargateWebService getAdminWeb() {
        return adminWebService;
    }

    public FargateApiService getCustomerApiService() {
        return customerApiService;
    }

    public FargateApiService getAdminApiService() {
        return adminApiService;
    }

}
