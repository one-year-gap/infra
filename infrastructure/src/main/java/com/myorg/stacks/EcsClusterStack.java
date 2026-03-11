package com.myorg.stacks;

import com.myorg.config.AppConfig;
import com.myorg.config.EnvKey;
import com.myorg.config.RepositoryConfig;
import com.myorg.constructs.FargateApiService;
import com.myorg.constructs.FargateBackgroundService;
import com.myorg.constructs.FargateWebService;
import com.myorg.props.FargateApiServiceProps;
import com.myorg.props.FargateBackgroundServiceProps;
import com.myorg.props.FargateWebServiceProps;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespace;
import software.constructs.Construct;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EcsClusterStack extends Stack {
    private final Cluster cluster;
    private final LogGroup ecsLogGroup;
    private final ILogGroup recommendationRealtimeLogGroup;
    private final ILogGroup analysisServerLogGroup;
    private final ISecret apiServerKafkaSecret;
    private final ISecret recommendationRealtimeRuntimeSecret;

    private final FargateApiService customerApiService;
    private final FargateApiService adminApiService;
    private final FargateBackgroundService recommendationRealtimeService;
    private final FargateBackgroundService analysisServerService;
    private final FargateWebService adminWebService;

    /**
     * ID 상수
     */
    private static final String CLUSTER_ID = "HolliverseCluster";
    private static final String LOG_GROUP_ID = "EcsLogGroup";
    private static final String RECOMMENDATION_REALTIME_LOG_GROUP_ID = "RecommendationRealtimeLogGroup";
    private static final String ANALYSIS_SERVER_LOG_GROUP_ID = "AnalysisServerLogGroup";
    private static final String API_SERVER_KAFKA_SECRET_ID = "ApiServerKafkaSecret";
    private static final String RECOMMENDATION_REALTIME_RUNTIME_SECRET_ID = "RecommendationRealtimeRuntimeSecret";
    private static final String RECOMMENDATION_REALTIME_REPOSITORY_ID = "RecommendationRealtimeRepo";

    private static final String ADMIN_WEB_ID = "AdminWeb";
    private static final String ADMIN_WEB_LOG_STREAM_PREFIX = "admin-web";

    private static final String ADMIN_API_ID = "AdminApi";
    private static final String ADMIN_API_LOG_STREAM_PREFIX = "admin-api";

    private static final String CUSTOMER_API_ID = "CustomerApi";
    private static final String CUSTOMER_API_LOG_STREAM_PREFIX = "customer-api";

    private static final String RECOMMENDATION_REALTIME_ID = "RecommendationRealtime";
    private static final String RECOMMENDATION_REALTIME_LOG_STREAM_PREFIX = "recommendation-realtime";
    private static final String ANALYSIS_SERVER_ID = "AnalysisServer";
    private static final String ANALYSIS_SERVER_LOG_STREAM_PREFIX = "analysis-server";

    /**
     * 서비스 상수
     */
    private static final int DESIRED_COUNT = 1;
    private static final String SPRING_PROFILES_ADMIN = "admin,prod";
    private static final String SPRING_PROFILES_CUSTOMER = "customer,prod";
    private static final String DOMAIN_NAME_SPACE = "ServiceNs";
    private static final String ADMIN_CLOUD_MAP_NAME = "admin-api";
    private static final String CUSTOMER_CLOUD_MAP_NAME = "customer-api";

    /**
     * DB 상수
     */
    private static final String DATABASE_NAME = "holliverse";

    public EcsClusterStack(
            Construct scope,
            String id,
            StackProps props,

            // NetworkStack에서 가져옴
            Vpc vpc,
            SecurityGroup adminWebSg,
            SecurityGroup adminApiSg,
            SecurityGroup customerApiSg,
            SecurityGroup recommendationRealtimeSg,
            SecurityGroup analysisServerSg,

            // EcrStack에서 내려오는 것
            Repository adminWebRepo,
            Repository apiServerRepo,

            // RdsStack에서 내려오는 것
            DatabaseInstance rds,
            Secret dbSecret,

            // MskStack에서 내려오는 것
            String mskClusterArn,
            String mskBootstrapBrokersSaslIam,

            // 포트/태그
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

        // 운영 계정에는 동일 이름의 로그 그룹이 이미 존재하므로 신규 생성이 아니라 import로 재사용한다.
        this.recommendationRealtimeLogGroup = LogGroup.fromLogGroupName(
                this,
                RECOMMENDATION_REALTIME_LOG_GROUP_ID,
                "/holliverse/ecs/recommendation-realtime"
        );

        this.analysisServerLogGroup = LogGroup.fromLogGroupName(
                this,
                ANALYSIS_SERVER_LOG_GROUP_ID,
                "/holliverse/ecs/analysis-server"
        );

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
        List<String> adminApiSecretsManagerArns = resolveArns(
                "ADMIN_API_RUNTIME_SECRET_ARN",
                "ADMIN_API_SECRETS_MANAGER_ARNS"
        );
        List<String> customerApiSecretsManagerArns = resolveArns(
                "CUSTOMER_API_RUNTIME_SECRET_ARN",
                "CUSTOMER_API_SECRETS_MANAGER_ARNS"
        );
        List<PolicyStatement> adminApiExtraTaskPolicies = resolveKmsDecryptPolicies(
                "ADMIN_API_RUNTIME_SECRET_KMS_KEY_ARN",
                "ADMIN_API_RUNTIME_SECRET_KMS_KEY_ARNS"
        );
        List<PolicyStatement> customerApiExtraTaskPolicies = resolveKmsDecryptPolicies(
                "CUSTOMER_API_RUNTIME_SECRET_KMS_KEY_ARN",
                "CUSTOMER_API_RUNTIME_SECRET_KMS_KEY_ARNS"
        );
        List<PolicyStatement> mskTaskPolicies = buildMskTaskPolicies(mskClusterArn);

        /**
         * 5) API Server Kafka secret
         * 운영 계정에는 동일 이름의 kafka secret이 이미 생성되어 있으므로 import로 재사용한다.
         */
        this.apiServerKafkaSecret = Secret.fromSecretNameV2(
                this,
                API_SERVER_KAFKA_SECRET_ID,
                AppConfig.getValueOrDefault(EnvKey.API_SERVER_KAFKA_SECRET_NAME)
        );
        String apiServerKafkaSecretArnPattern = buildSecretArnPattern(
                AppConfig.getValueOrDefault(EnvKey.API_SERVER_KAFKA_SECRET_NAME)
        );
        addIfPresent(adminApiSecretsManagerArns, apiServerKafkaSecretArnPattern);
        addIfPresent(customerApiSecretsManagerArns, apiServerKafkaSecretArnPattern);

        /**
         * 6) Recommendation runtime secret
         * 운영 계정에는 동일 이름의 runtime secret이 이미 존재하므로 import로 재사용한다.
         */
        this.recommendationRealtimeRuntimeSecret = Secret.fromSecretNameV2(
                this,
                RECOMMENDATION_REALTIME_RUNTIME_SECRET_ID,
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_RUNTIME_SECRET_NAME)
        );

        /**
         * 7) Recommendation repository import
         * 현재 운영 ECR에 이미 존재하는 Python repo를 가져와 ECS 서비스에서 재사용한다.
         */
        IRepository recommendationRealtimeRepo = Repository.fromRepositoryName(
                this,
                RECOMMENDATION_REALTIME_REPOSITORY_ID,
                RepositoryConfig.getRecommendationRealtimeRepository()
        );

        /**
         * 8) Props Setup
         */
        Map<String, String> adminApiEnvironment = buildAdminApiEnvironment(mskBootstrapBrokersSaslIam);
        Map<String, String> customerApiEnvironment = buildCustomerApiEnvironment(mskBootstrapBrokersSaslIam);

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
                SPRING_PROFILES_ADMIN,
                dbUrl,
                dbSecret,
                adminApiEnvironment,
                serviceNs,
                ADMIN_CLOUD_MAP_NAME,
                adminApiSecretsManagerArns,
                List.of(),
                mergePolicies(adminApiExtraTaskPolicies, mskTaskPolicies)
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
                SPRING_PROFILES_CUSTOMER,
                dbUrl,
                dbSecret,
                customerApiEnvironment,
                serviceNs,
                CUSTOMER_CLOUD_MAP_NAME,
                customerApiSecretsManagerArns,
                List.of(),
                mergePolicies(customerApiExtraTaskPolicies, mskTaskPolicies)
        );

        int recommendationRealtimePort = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_PORT));
        int recommendationRealtimeDesiredCount = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_DESIRED_COUNT));
        int analysisServerPort = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_PORT));
        int analysisServerDesiredCount = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_DESIRED_COUNT));
        Map<String, String> recommendationRealtimeEnvironment = buildRecommendationRealtimeEnvironment(
                recommendationRealtimePort,
                adminApiPort,
                mskBootstrapBrokersSaslIam
        );
        Map<String, String> analysisServerEnvironment = buildAnalysisServerEnvironment(
                analysisServerPort,
                mskBootstrapBrokersSaslIam
        );

        FargateBackgroundServiceProps recommendationRealtimeServiceProps = new FargateBackgroundServiceProps(
                this,
                RECOMMENDATION_REALTIME_ID,
                cluster,
                recommendationRealtimeRepo,
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_IMAGE_TAG),
                recommendationRealtimeSg,
                recommendationRealtimeLogGroup,
                RECOMMENDATION_REALTIME_LOG_STREAM_PREFIX,
                privateSubnets,
                null,
                512,
                1024,
                recommendationRealtimeDesiredCount,
                true,
                recommendationRealtimeEnvironment,
                List.of(),
                recommendationRealtimePort,
                recommendationRealtimeRuntimeSecret,
                buildRecommendationRuntimeSecretMapping(),
                serviceNs,
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_CLOUD_MAP_NAME),
                List.of(),
                mskTaskPolicies
        );

        FargateBackgroundServiceProps analysisServerServiceProps = new FargateBackgroundServiceProps(
                this,
                ANALYSIS_SERVER_ID,
                cluster,
                recommendationRealtimeRepo,
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_IMAGE_TAG),
                analysisServerSg,
                analysisServerLogGroup,
                ANALYSIS_SERVER_LOG_STREAM_PREFIX,
                privateSubnets,
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_SERVICE_NAME),
                512,
                1024,
                analysisServerDesiredCount,
                true,
                analysisServerEnvironment,
                List.of(),
                analysisServerPort,
                recommendationRealtimeRuntimeSecret,
                buildRecommendationRuntimeSecretMapping(),
                serviceNs,
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_CLOUD_MAP_NAME),
                List.of(),
                mskTaskPolicies
        );

        /**
         * 9) Admin Web - Next.js
         */
        this.adminWebService = new FargateWebService(adminWebServiceProps);

        /**
         * 10) Admin API - Spring Boot
         */
        this.adminApiService = new FargateApiService(adminApiServiceProps);

        /**
         * 11) Customer API - Spring Boot
         */
        this.customerApiService = new FargateApiService(customerApiServiceProps);

        /**
         * 12) Recommendation Realtime - Python internal service
         * batch는 같은 레포/이미지를 쓰더라도 상시 Service로 올리지 않고
         * EventBridge/StepFunctions가 1회성 Task를 실행하는 방향으로 분리한다.
         */
        this.recommendationRealtimeService = new FargateBackgroundService(recommendationRealtimeServiceProps);
        this.analysisServerService = new FargateBackgroundService(analysisServerServiceProps);
    }

    public Cluster getCluster() {
        return cluster;
    }

    public LogGroup getEcsLogGroup() {
        return ecsLogGroup;
    }

    public ILogGroup getRecommendationRealtimeLogGroup() {
        return recommendationRealtimeLogGroup;
    }

    public ILogGroup getAnalysisServerLogGroup() {
        return analysisServerLogGroup;
    }

    public ISecret getApiServerKafkaSecret() {
        return apiServerKafkaSecret;
    }

    public ISecret getRecommendationRealtimeRuntimeSecret() {
        return recommendationRealtimeRuntimeSecret;
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

    public FargateBackgroundService getRecommendationRealtimeService() {
        return recommendationRealtimeService;
    }

    public FargateBackgroundService getAnalysisServerService() {
        return analysisServerService;
    }

    private Map<String, String> buildAdminApiEnvironment(String mskBootstrapBrokersSaslIam) {
        Map<String, String> env = new HashMap<>();
        env.put("DB_POOL_MAX", "10");
        env.put("DB_POOL_MIN", "1");
        env.put("KAFKA_SECRET_NAME", AppConfig.getValueOrDefault(EnvKey.API_SERVER_KAFKA_SECRET_NAME));
        // api-server가 secret import 실패 시 localhost 기본값으로 떨어지지 않도록
        // Kafka 연결값은 컨테이너 env로도 직접 주입한다.
        env.put("MSK_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
        env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
        env.put("KAFKA_SASL_MECHANISM", "AWS_MSK_IAM");
        env.put("KAFKA_SASL_JAAS_CONFIG", "software.amazon.msk.auth.iam.IAMLoginModule required;");
        env.put("KAFKA_SASL_CALLBACK_HANDLER_CLASS", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        // admin-api는 private recommendation service를 내부 DNS로 호출한다.
        env.put(
                "FASTAPI_BASE_URL",
                "http://" + AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_CLOUD_MAP_NAME) + "."
                        + AppConfig.getInternalDomainName() + ":"
                        + AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_PORT)
        );
        return env;
    }

    private Map<String, String> buildCustomerApiEnvironment(String mskBootstrapBrokersSaslIam) {
        Map<String, String> env = new HashMap<>();
        env.put("DB_POOL_MAX", "10");
        env.put("DB_POOL_MIN", "1");
        env.put("KAFKA_SECRET_NAME", AppConfig.getValueOrDefault(EnvKey.API_SERVER_KAFKA_SECRET_NAME));
        env.put("MSK_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
        env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
        env.put("KAFKA_SASL_MECHANISM", "AWS_MSK_IAM");
        env.put("KAFKA_SASL_JAAS_CONFIG", "software.amazon.msk.auth.iam.IAMLoginModule required;");
        env.put("KAFKA_SASL_CALLBACK_HANDLER_CLASS", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        return env;
    }

    private Map<String, String> buildRecommendationRealtimeEnvironment(
            int recommendationRealtimePort,
            int adminApiPort,
            String mskBootstrapBrokersSaslIam
    ) {
        Map<String, String> env = new HashMap<>();
        env.put("APP_MODE", "realtime");
        env.put("APP_ENV", "prod");
        env.put("DEBUG", "false");
        env.put("APP_PORT", String.valueOf(recommendationRealtimePort));
        env.put("PYTHONUNBUFFERED", "1");
        env.put("KAFKA_CONSUMER_ENABLED", "false");

        env.put("POSTGRES_POOL_MIN_SIZE", "1");
        env.put("POSTGRES_POOL_MAX_SIZE", "2");
        env.put(
                "ADMIN_API_BASE_URL",
                "http://" + ADMIN_CLOUD_MAP_NAME + "." + AppConfig.getInternalDomainName() + ":" + adminApiPort
        );
        return env;
    }

    private Map<String, String> buildAnalysisServerEnvironment(
            int analysisServerPort,
            String mskBootstrapBrokersSaslIam
    ) {
        Map<String, String> env = new HashMap<>();
        env.put("APP_MODE", "analysis-server");
        env.put("APP_ENV", "prod");
        env.put("DEBUG", "false");
        env.put("APP_PORT", String.valueOf(analysisServerPort));
        env.put("PYTHONUNBUFFERED", "1");

        env.put("KAFKA_CONSUMER_ENABLED", "true");
        env.put("KAFKA_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
        env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
        env.put("KAFKA_SASL_MECHANISM", "OAUTHBEARER");
        env.put("KAFKA_AWS_REGION", AppConfig.getRegion());
        env.put("KAFKA_ANALYSIS_REQUEST_TOPIC", "analysis.request.v1");
        env.put("KAFKA_ANALYSIS_RESPONSE_TOPIC", "analysis.response.v1");
        env.put("KAFKA_CONSUMER_GROUP_ID", "counseling-analytics-consumer");
        env.put("KAFKA_AUTO_OFFSET_RESET", "earliest");
        env.put("KAFKA_BATCH_SIZE", "1000");
        env.put("KAFKA_POLL_TIMEOUT_MS", "1000");
        env.put("KAFKA_LOG_EACH_MESSAGE", "true");
        env.put("KAFKA_LOG_RESULT_LIMIT", "20");
        env.put("KAFKA_RESPONSE_MAX_ATTEMPTS", "3");

        env.put("POSTGRES_POOL_MIN_SIZE", "1");
        env.put("POSTGRES_POOL_MAX_SIZE", "2");
        return env;
    }

    private Map<String, String> buildRecommendationRuntimeSecretMapping() {
        return Map.of(
                "POSTGRES_HOST", "POSTGRES_HOST",
                "POSTGRES_PORT", "POSTGRES_PORT",
                "POSTGRES_DB", "POSTGRES_DB",
                "POSTGRES_USER", "POSTGRES_USER",
                "POSTGRES_PASSWORD", "POSTGRES_PASSWORD"
        );
    }

    private List<String> resolveArns(String singleArnEnvKey, String csvArnEnvKey) {
        List<String> arns = new ArrayList<>();
        addIfPresent(arns, AppConfig.getOptionalValueOrDefault(singleArnEnvKey, ""));

        String csv = AppConfig.getOptionalValueOrDefault(csvArnEnvKey, "");
        if (!csv.isBlank()) {
            for (String arn : csv.split(",")) {
                addIfPresent(arns, arn);
            }
        }
        return arns;
    }

    private void addIfPresent(List<String> target, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && !target.contains(trimmed)) {
            target.add(trimmed);
        }
    }

    private String buildSecretArnPattern(String secretName) {
        return String.format(
                "arn:aws:secretsmanager:%s:%s:secret:%s*",
                this.getRegion(),
                this.getAccount(),
                secretName
        );
    }

    private List<PolicyStatement> resolveKmsDecryptPolicies(String singleArnEnvKey, String csvArnEnvKey) {
        List<String> kmsKeyArns = resolveArns(singleArnEnvKey, csvArnEnvKey);
        if (kmsKeyArns.isEmpty()) {
            return List.of();
        }

        return List.of(
                PolicyStatement.Builder.create()
                        .actions(List.of("kms:Decrypt"))
                        .resources(kmsKeyArns)
                        .build()
        );
    }

    private List<PolicyStatement> mergePolicies(List<PolicyStatement> left, List<PolicyStatement> right) {
        List<PolicyStatement> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged;
    }

    private List<PolicyStatement> buildMskTaskPolicies(String mskClusterArn) {
        // Stack이 가진 region/account 컨텍스트를 사용해야 CDK 테스트에서도 외부 환경변수에 의존하지 않는다.
        String region = this.getRegion();
        String accountId = this.getAccount();
        String clusterName = AppConfig.getValueOrDefault(EnvKey.MSK_CLUSTER_NAME);

        String topicArnPattern = String.format(
                "arn:aws:kafka:%s:%s:topic/%s/*",
                region,
                accountId,
                clusterName
        );
        String groupArnPattern = String.format(
                "arn:aws:kafka:%s:%s:group/%s/*",
                region,
                accountId,
                clusterName
        );

        return List.of(
                PolicyStatement.Builder.create()
                        .actions(List.of(
                                "kafka-cluster:Connect",
                                "kafka-cluster:DescribeCluster"
                        ))
                        .resources(List.of(mskClusterArn))
                        .build(),
                PolicyStatement.Builder.create()
                        .actions(List.of(
                                "kafka-cluster:DescribeTopic",
                                "kafka-cluster:ReadData",
                                "kafka-cluster:WriteData"
                        ))
                        .resources(List.of(topicArnPattern))
                        .build(),
                PolicyStatement.Builder.create()
                        .actions(List.of(
                                "kafka-cluster:DescribeGroup",
                                "kafka-cluster:AlterGroup"
                        ))
                        .resources(List.of(groupArnPattern))
                        .build()
        );
    }
}
