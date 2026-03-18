package com.myorg;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import com.myorg.config.monitoring.MonitoringConfig;
import com.myorg.config.NetworkStackConfig;
import com.myorg.config.PortConfig;
import com.myorg.config.OnDemandWorkflowConfig;
import com.myorg.config.WafConfig;
import com.myorg.props.ApplicationLoadBalancerProps;
import com.myorg.props.DnsProps;
import com.myorg.props.MonitoringStackProps;
import com.myorg.stacks.*;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.route53.IHostedZone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class InfrastructureApp {
    private static final String ROUTE53_STACK_ID = "Route53Stack";
    private static final String NETWORK_STACK_ID = "NetworkStack";
    private static final String ECR_STACK_ID = "EcrStack";
    private static final String RDS_STACK_ID = "RdsStack";
    private static final String ECS_CLUSTER_STACK_ID = "EcsClusterStack";
    private static final String ALB_STACK_ID = "AlbStack";
    private static final String ALB_WAF_STACK_ID = "AlbWafStack";
    private static final String DNS_STACK_ID = "DnsStack";
    private static final String MONITORING_STACK_ID = "MonitoringStack";
    private static final String MSK_STACK_ID = "MskStack";
    private static final String CLICK_LOG_BUCKET_STACK_ID = "ClickLogBucketStack";
    private static final String MSK_CONNECT_STACK_ID = "MskConnectStack";
    private static final String DEFAULT_IMAGE_TAG = "latest";
    private static final String LOG_ARCHIVE_STACK_ID = "LogArchiveStack";
    private static final String ON_DEMAND_WORKFLOW_STACK_ID = "OnDemandWorkflowStack";
    private static final String ON_DEMAND_LOCK_STACK_ID = "OnDemandLockStack";

    private static final String DEFAULT_DEPLOY_MODE = "route53";
    private static final String DEPLOY_MODE_ROUTE53 = "route53";
    private static final String DEPLOY_MODE_ECR = "ecr";
    private static final String DEPLOY_MODE_NETWORK = "network";
    private static final String DEPLOY_MODE_RDS = "rds";
    private static final String DEPLOY_MODE_ECS = "ecs";
    private static final String DEPLOY_MODE_ALB = "alb";
    private static final String DEPLOY_MODE_ALB_WAF = "alb-waf";
    private static final String DEPLOY_MODE_DNS = "dns";
    private static final String DEPLOY_MODE_MONITORING = "monitoring";
    private static final String DEPLOY_MODE_MSK = "msk";
    private static final String DEPLOY_MODE_MSK_CONNECT = "msk-connect";
    private static final String DEPLOY_MODE_FULL = "full";
    private static final String DEPLOY_MODE_LOG_ARCHIVE = "log-archive";
    private static final String DEPLOY_MODE_ON_DEMAND_WORKFLOW = "on-demand-workflow";
    private static final String DEPLOY_MODE_ON_DEMAND_LOCK = "on-demand-lock";

    /**
     * deployMode에 따라 배포할 스택 체인을 선택.
     */
    public static void main(String[] args) {
        App app = new App();
        DeploymentContext deploymentContext = resolveDeploymentContext(app);

        switch (deploymentContext.deployMode()) {
            case DEPLOY_MODE_ROUTE53 -> deployRoute53(deploymentContext);
            case DEPLOY_MODE_ECR -> deployEcr(deploymentContext);
            case DEPLOY_MODE_NETWORK -> deployNetwork(deploymentContext);
            case DEPLOY_MODE_RDS -> deployRds(deploymentContext);
            case DEPLOY_MODE_ECS -> deployEcs(deploymentContext);
            case DEPLOY_MODE_ALB -> deployAlb(deploymentContext);
            case DEPLOY_MODE_ALB_WAF -> deployAlbWaf(deploymentContext);
            case DEPLOY_MODE_MONITORING -> deployMonitoring(deploymentContext);
            case DEPLOY_MODE_MSK -> deployMsk(deploymentContext);
            case DEPLOY_MODE_MSK_CONNECT -> deployMskConnect(deploymentContext);
            case DEPLOY_MODE_DNS -> deployDns(deploymentContext);
            case DEPLOY_MODE_FULL -> deployFull(deploymentContext);
            case DEPLOY_MODE_LOG_ARCHIVE -> deployLogArchive(deploymentContext);
            case DEPLOY_MODE_ON_DEMAND_LOCK -> deployOnDemandLock(deploymentContext);
            case DEPLOY_MODE_ON_DEMAND_WORKFLOW -> deployOnDemandWorkflow(deploymentContext);
            default -> throw new IllegalArgumentException("지원하지 않는 deployMode : "
                                                          + deploymentContext.deployMode());
        }

        app.synth();
    }


    /**
     * Route53 Hosted Zone 스택 배포
     */
    private static void deployRoute53(DeploymentContext context) {
        new Route53Stack(context.app(), ROUTE53_STACK_ID, context.stackProps(), context.domainName());
    }

    /**
     * ECR 리포지토리 스택 배포
     */
    private static void deployEcr(DeploymentContext context) {
        new EcrStack(context.app(), ECR_STACK_ID, context.stackProps());
    }

    /**
     * 네트워크(VPC/SG) 스택 배포
     */
    private static void deployNetwork(DeploymentContext context) {
        createNetworkStack(context);
    }

    /**
     * 네트워크 + RDS 스택 배포
     */
    private static void deployRds(DeploymentContext context) {
        NetworkStack networkStack = createNetworkStack(context);
        createRdsStack(context, networkStack);
    }

    /**
     * ECS 스택 배포
     */
    private static void deployEcs(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        createEcsClusterStack(context, baseStacks);
    }

    /**
     * ECS 구성 -> ALB 스택 배포
     */
    private static void deployAlb(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
    }

    /**
     * ALB + WAF 스택 배포
     */
    private static void deployAlbWaf(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
    }

    /**
     * 네트워크 + 모니터링(Grafana) 스택 배포
     */
    private static void deployMonitoring(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        MskStack mskStack = createMskStack(context, baseStacks.networkStack());
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks, mskStack);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
        createDnsStack(context, albStack);

        MonitoringStackProps props = new MonitoringStackProps(
                baseStacks.networkStack().getVpc(),
                baseStacks.networkStack().getMonitoringSg(),
                baseStacks.networkStack().getDbSg(),
                baseStacks.networkStack().getAdminApiSg(),
                baseStacks.networkStack().getCustomerApiSg(),
                baseStacks.networkStack().getKafkaBrokerSg(),
                mskStack.getBootstrapBrokersSaslIam(),
                PortConfig.getAdminServerPort(),
                PortConfig.getCustomerServerPort(),
                MonitoringConfig.fromEnv()
        );
        new MonitoringStack(
                context.app(),
                MONITORING_STACK_ID,
                context.stackProps(),
                props
        );
    }

    /**
     * Recommendation realtime consumer 전용 Provisioned MSK 스택 배포
     */
    private static void deployMsk(DeploymentContext context) {
        // NetworkStack을 참조하는 기존 스택들을 함께 synth해서
        // cross-stack export가 제거되지 않도록 현재 그래프를 유지한다.
        BaseStacks baseStacks = createBaseStacks(context);
        MskStack mskStack = createMskStack(context, baseStacks.networkStack());
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks, mskStack);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
        createDnsStack(context, albStack);

        MonitoringStackProps monitoringProps = new MonitoringStackProps(
                baseStacks.networkStack().getVpc(),
                baseStacks.networkStack().getMonitoringSg(),
                baseStacks.networkStack().getDbSg(),
                baseStacks.networkStack().getAdminApiSg(),
                baseStacks.networkStack().getCustomerApiSg(),
                baseStacks.networkStack().getKafkaBrokerSg(),
                mskStack.getBootstrapBrokersSaslIam(),
                PortConfig.getAdminServerPort(),
                PortConfig.getCustomerServerPort(),
                MonitoringConfig.fromEnv()
        );
        new MonitoringStack(
                context.app(),
                MONITORING_STACK_ID,
                context.stackProps(),
                monitoringProps
        );

        mskStack.getNode().addDependency(baseStacks.networkStack());
    }

    /**
     * click log sink 전용 스택 배포.
     */
    private static void deployMskConnect(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        MskStack mskStack = createMskStack(context, baseStacks.networkStack());
        ClickLogBucketStack clickLogBucketStack = createClickLogBucketStack(context);
        createMskConnectStack(context, baseStacks.networkStack(), mskStack, clickLogBucketStack);
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks, mskStack);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
        createDnsStack(context, albStack);

        MonitoringStackProps monitoringProps = new MonitoringStackProps(
                baseStacks.networkStack().getVpc(),
                baseStacks.networkStack().getMonitoringSg(),
                baseStacks.networkStack().getDbSg(),
                baseStacks.networkStack().getAdminApiSg(),
                baseStacks.networkStack().getCustomerApiSg(),
                baseStacks.networkStack().getKafkaBrokerSg(),
                mskStack.getBootstrapBrokersSaslIam(),
                PortConfig.getAdminServerPort(),
                PortConfig.getCustomerServerPort(),
                MonitoringConfig.fromEnv()
        );
        new MonitoringStack(
                context.app(),
                MONITORING_STACK_ID,
                context.stackProps(),
                monitoringProps
        );
    }

    /**
     * Loki용 S3 Bucket Stack 배포
     */
    private static void deployLogArchive(DeploymentContext deploymentContext) {
        new LogArchiveStack(
                deploymentContext.app(),
                LOG_ARCHIVE_STACK_ID,
                deploymentContext.stackProps(),
                AppConfig.getValueOrDefault(EnvKey.MONITORING_LOKI_S3_BUCKET)
        );
    }

    /**
     * analysis-server batch 워크플로우 스택 배포
     *
     * <p>주의:
     * - 이 모드는 기존 ECS 클러스터/TaskDefinition 리소스를 \"재사용(import)\"하는 워크플로우 전용 배포 모드다.
     * - 필요한 값은 환경변수(EnvKey.ON_DEMAND_*)로 주입해야 한다.
     */
    private static void deployOnDemandWorkflow(DeploymentContext context) {
        OnDemandWorkflowConfig config = OnDemandWorkflowConfig.fromEnv();
        new OnDemandWorkflowStack(
                context.app(),
                ON_DEMAND_WORKFLOW_STACK_ID,
                context.stackProps(),
                config
        );
    }

    /**
     * analysis-server batch 선점 락 테이블(DynamoDB) 전용 스택 배포.
     *
     * <p>운영 권장:
     * - 먼저 on-demand-lock 모드로 락 테이블을 생성한다.
     * - 이후 on-demand-workflow 모드에서 해당 테이블을 import해 사용한다.
     */
    private static void deployOnDemandLock(DeploymentContext context) {
        new OnDemandLockStack(
                context.app(),
                ON_DEMAND_LOCK_STACK_ID,
                context.stackProps()
        );
    }

    /**
     * ALB까지 구성 -> DNS 레코드 스택 배포
     */
    private static void deployDns(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
        createDnsStack(context, albStack);
    }

    /**
     * click log sink까지 포함한 전체 스택 배포.
     */
    private static void deployFull(DeploymentContext context) {
        BaseStacks baseStacks = createBaseStacks(context);
        EcsClusterStack ecsClusterStack = createEcsClusterStack(context, baseStacks);
        AlbStack albStack = createAlbStack(context, baseStacks.networkStack(), ecsClusterStack);
        createAlbWafStack(context, albStack);
        createDnsStack(context, albStack);
    }

    /**
     * 공통 기반 스택(Network/ECR/RDS)을 생성.
     */
    private static BaseStacks createBaseStacks(DeploymentContext context) {
        NetworkStack networkStack = createNetworkStack(context);
        EcrStack ecrStack = createEcrStack(context);
        RdsStack rdsStack = createRdsStack(context, networkStack);
        return new BaseStacks(networkStack, ecrStack, rdsStack);
    }

    /**
     * 네트워크 설정값 NetworkStack을 생성
     */
    private static NetworkStack createNetworkStack(DeploymentContext context) {
        NetworkStackConfig networkConfig = new NetworkStackConfig(
                AppConfig.getAdminAllowedCidrs(),
                PortConfig.getAdminServerPort(),
                PortConfig.getAdminWebPort(),
                PortConfig.getCustomerServerPort(),
                PortConfig.getCustomerWebPort()
        );

        return new NetworkStack(
                context.app(),
                NETWORK_STACK_ID,
                context.stackProps(),
                networkConfig
        );
    }

    /**
     * ECR 스택 생성
     */
    private static EcrStack createEcrStack(DeploymentContext context) {
        return new EcrStack(
                context.app(),
                ECR_STACK_ID,
                context.stackProps()
        );
    }

    /**
     * RDS 스택 생성
     */
    private static RdsStack createRdsStack(DeploymentContext context, NetworkStack networkStack) {
        return new RdsStack(
                context.app(),
                RDS_STACK_ID,
                context.stackProps(),
                networkStack.getVpc(),
                networkStack.getDbSg()
        );
    }

    /**
     * 이미지 태그 +  기반 스택 정보 ECS 스택 생성
     */
    private static MskStack createMskStack(DeploymentContext context, NetworkStack networkStack) {
        MskStack mskStack = new MskStack(
                context.app(),
                MSK_STACK_ID,
                context.stackProps(),
                networkStack.getVpc(),
                networkStack.getKafkaBrokerSg()
        );
        mskStack.getNode().addDependency(networkStack);
        return mskStack;
    }

    /**
     * raw click log 버킷 스택 생성.
     */
    private static ClickLogBucketStack createClickLogBucketStack(DeploymentContext context) {
        return new ClickLogBucketStack(
                context.app(),
                CLICK_LOG_BUCKET_STACK_ID,
                context.stackProps(),
                AppConfig.getValueOrDefault(EnvKey.CLICK_LOG_BUCKET_NAME)
        );
    }

    /**
     * S3 sink용 MSK Connect 스택 생성.
     */
    private static MskConnectStack createMskConnectStack(
            DeploymentContext context,
            NetworkStack networkStack,
            MskStack mskStack,
            ClickLogBucketStack clickLogBucketStack
    ) {
        return new MskConnectStack(
                context.app(),
                MSK_CONNECT_STACK_ID,
                context.stackProps(),
                networkStack.getVpc(),
                networkStack.getKafkaBrokerSg(),
                networkStack.getKafkaConnectSg(),
                AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME),
                mskStack.getBootstrapBrokersSaslIam(),
                mskStack.getCluster().getAttrArn(),
                mskStack.getClickLogTopicName(),
                clickLogBucketStack.getBucket()
        );
    }

    /**
     * ECS 서비스 스택 생성.
     */
    private static EcsClusterStack createEcsClusterStack(DeploymentContext context, BaseStacks baseStacks) {
        return createEcsClusterStack(
                context,
                baseStacks,
                AppConfig.getOptionalValueOrDefault("ECS_MSK_CLUSTER_NAME_OVERRIDE", ""),
                AppConfig.getOptionalValueOrDefault("ECS_MSK_CLUSTER_ARN_OVERRIDE", ""),
                AppConfig.getOptionalValueOrDefault("ECS_MSK_BOOTSTRAP_BROKERS_OVERRIDE", "")
        );
    }

    private static EcsClusterStack createEcsClusterStack(DeploymentContext context, BaseStacks baseStacks, MskStack mskStack) {
        return createEcsClusterStack(
                context,
                baseStacks,
                AppConfig.getOptionalValueOrDefault(
                        "ECS_MSK_CLUSTER_NAME_OVERRIDE",
                        AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME)
                ),
                AppConfig.getOptionalValueOrDefault(
                        "ECS_MSK_CLUSTER_ARN_OVERRIDE",
                        mskStack.getCluster().getAttrArn()
                ),
                AppConfig.getOptionalValueOrDefault(
                        "ECS_MSK_BOOTSTRAP_BROKERS_OVERRIDE",
                        mskStack.getBootstrapBrokersSaslIam()
                )
        );
    }

    private static EcsClusterStack createEcsClusterStack(
            DeploymentContext context,
            BaseStacks baseStacks,
            String ecsMskClusterName,
            String ecsMskClusterArn,
            String ecsMskBootstrapBrokers
    ) {
        String adminWebImageTag = resolvePinnedImageTag(
                "ADMIN_WEB_IMAGE_TAG",
                "one-year-gap/admin-fe",
                "EcsClusterStack-AdminWebService",
                DEFAULT_IMAGE_TAG
        );
        String legacyApiImageTag = resolvePinnedImageTag(
                "API_IMAGE_TAG",
                "one-year-gap/api-server",
                "EcsClusterStack-AdminApiService",
                DEFAULT_IMAGE_TAG
        );
        String adminApiImageTag = resolvePinnedImageTag(
                "ADMIN_API_IMAGE_TAG",
                "one-year-gap/api-server",
                "EcsClusterStack-AdminApiService",
                legacyApiImageTag
        );
        String customerApiImageTag = resolvePinnedImageTag(
                "CUSTOMER_API_IMAGE_TAG",
                "one-year-gap/api-server",
                "EcsClusterStack-CustomerApiService",
                legacyApiImageTag
        );
        String intelligenceServerImageTag = resolvePinnedImageTag(
                EnvKey.RECOMMENDATION_REALTIME_IMAGE_TAG.key(),
                "one-year-gap/counseling-analytics",
                "intelligence-server",
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_IMAGE_TAG)
        );
        String logServerImageTag = resolvePinnedImageTag(
                EnvKey.LOG_SERVER_IMAGE_TAG.key(),
                "one-year-gap/log-server",
                "log-server",
                AppConfig.getValueOrDefault(EnvKey.LOG_SERVER_IMAGE_TAG)
        );

        return new EcsClusterStack(
                context.app(),
                ECS_CLUSTER_STACK_ID,
                context.stackProps(),
                baseStacks.networkStack().getVpc(),
                baseStacks.networkStack().getAdminWebSg(),
                baseStacks.networkStack().getAdminApiSg(),
                baseStacks.networkStack().getCustomerApiSg(),
                baseStacks.networkStack().getRecommendationRealtimeSg(),
                baseStacks.networkStack().getAnalysisServerSg(),
                // NetworkStack export 충돌 회피용 임시 SG 공유
                baseStacks.networkStack().getAnalysisServerSg(),
                baseStacks.ecrStack().getAdminWebRepo(),
                baseStacks.ecrStack().getApiServerRepo(),
                baseStacks.ecrStack().getLogServerRepo(),
                baseStacks.rdsStack().getRds(),
                baseStacks.rdsStack().getDbSecret(),
                ecsMskClusterName,
                ecsMskClusterArn,
                ecsMskBootstrapBrokers,
                PortConfig.getAdminWebPort(),
                PortConfig.getAdminServerPort(),
                PortConfig.getCustomerServerPort(),
                adminWebImageTag,
                adminApiImageTag,
                customerApiImageTag,
                intelligenceServerImageTag,
                logServerImageTag
        );
    }

    private static String resolvePinnedImageTag(
            String envKey,
            String expectedRepositorySuffix,
            String serviceNamePattern,
            String fallbackTag
    ) {
        String liveTag = resolveLiveImageTag(expectedRepositorySuffix, serviceNamePattern);
        if (liveTag != null && !liveTag.isBlank()) {
            return liveTag;
        }

        String explicit = AppConfig.getOptionalValue(envKey);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }

        return fallbackTag;
    }

    private static String resolveLiveImageTag(String expectedRepositorySuffix, String serviceNamePattern) {
        String clusterArn = runAwsCli(
                "ecs", "list-clusters",
                "--query", "clusterArns[?contains(@, 'HolliverseCluster')]|[0]",
                "--output", "text"
        );
        if (clusterArn == null || clusterArn.isBlank() || "None".equals(clusterArn)) {
            return null;
        }

        String clusterName = clusterArn.substring(clusterArn.lastIndexOf('/') + 1);
        String serviceArns = runAwsCli(
                "ecs", "list-services",
                "--cluster", clusterName,
                "--query", "serviceArns",
                "--output", "text"
        );
        if (serviceArns == null || serviceArns.isBlank()) {
            return null;
        }

        String matchedService = Arrays.stream(serviceArns.split("\\s+"))
                .map(String::trim)
                .filter(value -> !value.isBlank() && !"None".equals(value))
                .map(value -> value.substring(value.lastIndexOf('/') + 1))
                .filter(value -> value.contains(serviceNamePattern))
                .findFirst()
                .orElse(null);
        if (matchedService == null) {
            return null;
        }

        String taskDefinitionArn = runAwsCli(
                "ecs", "describe-services",
                "--cluster", clusterName,
                "--services", matchedService,
                "--query", "services[0].taskDefinition",
                "--output", "text"
        );
        if (taskDefinitionArn == null || taskDefinitionArn.isBlank() || "None".equals(taskDefinitionArn)) {
            return null;
        }

        String imageUri = runAwsCli(
                "ecs", "describe-task-definition",
                "--task-definition", taskDefinitionArn,
                "--query", "taskDefinition.containerDefinitions[0].image",
                "--output", "text"
        );
        if (imageUri == null || imageUri.isBlank() || "None".equals(imageUri)) {
            return null;
        }
        if (!imageUri.contains("/" + expectedRepositorySuffix + ":")) {
            return null;
        }

        int tagSeparator = imageUri.lastIndexOf(':');
        if (tagSeparator < 0 || tagSeparator == imageUri.length() - 1) {
            return null;
        }
        return imageUri.substring(tagSeparator + 1);
    }

    private static String runAwsCli(String... args) {
        List<String> command = new ArrayList<>();
        command.add("aws");
        command.addAll(List.of(args));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                output = reader.lines().reduce("", (left, right) -> left + right + "\n");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return null;
            }

            String trimmed = output.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 인증서 ARN + 서비스 타깃 -> ALB 스택 생성
     */
    private static AlbStack createAlbStack(DeploymentContext context, NetworkStack networkStack, EcsClusterStack ecsClusterStack) {
        ICertificate customerCert = Certificate.fromCertificateArn(
                networkStack,
                "CustomerAlbCert",
                AppConfig.getCustomerCertArn()
        );
        ICertificate adminCert = Certificate.fromCertificateArn(
                networkStack,
                "AdminAlbCert",
                AppConfig.getAdminCertArn()
        );

        return new AlbStack(
                context.app(),
                ALB_STACK_ID,
                context.stackProps(),
                new ApplicationLoadBalancerProps(
                        networkStack.getVpc(),
                        networkStack.getCustomerAlbSg(),
                        networkStack.getAdminAlbSg(),
                        ecsClusterStack.getCustomerApiService().getService(),
                        PortConfig.getCustomerServerPort(),
                        ecsClusterStack.getAdminWeb().getService(),
                        PortConfig.getAdminWebPort(),
                        customerCert,
                        adminCert
                )
        );
    }

    /**
     * ALB 앞단 WAF 스택 생성
     */
    private static AlbWafStack createAlbWafStack(DeploymentContext context, AlbStack albStack) {
        return new AlbWafStack(
                context.app(),
                ALB_WAF_STACK_ID,
                context.stackProps(),
                albStack.getCustomerAlb(),
                albStack.getAdminAlb(),
                WafConfig.fromEnv()
        );
    }

    /**
     * 기존 Hosted Zone 조회 -> DNS 레코드 스택 생성
     */
    private static DnsStack createDnsStack(DeploymentContext context, AlbStack albStack) {
        IHostedZone hostedZone = HostedZone.fromLookup(
                albStack,
                "ExistingHostedZone",
                HostedZoneProviderProps.builder()
                        .domainName(context.domainName())
                        .build()
        );

        return new DnsStack(
                context.app(),
                DNS_STACK_ID,
                context.stackProps(),
                new DnsProps(
                        hostedZone,
                        albStack.getCustomerAlb(),
                        albStack.getAdminAlb()
                )
        );
    }

    /**
     * 실행 컨텍스트 공통 배포 정보(계정/리전/도메인/모드)를 구성
     */
    private static DeploymentContext resolveDeploymentContext(App app) {
        String deployMode = AppConfig.getDeployMode(DEFAULT_DEPLOY_MODE);
        String domainName = AppConfig.getDomainName();
        String region = AppConfig.getRegion();
        String account = AppConfig.getAccountId();

        StackProps stackProps = StackProps.builder()
                .env(Environment.builder()
                        .account(account)
                        .region(region)
                        .build())
                .build();

        return new DeploymentContext(app, stackProps, domainName, deployMode);
    }

    private record DeploymentContext(App app, StackProps stackProps, String domainName, String deployMode) {
    }

    private record BaseStacks(NetworkStack networkStack, EcrStack ecrStack, RdsStack rdsStack) {
    }
}
