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
import java.util.LinkedHashMap;
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
    private final FargateBackgroundService logServerService;
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
    private static final String LOG_SERVER_ID = "LogServer";
    private static final String LOG_SERVER_LOG_STREAM_PREFIX = "log-server";
    private static final String ERROR_LOG_TOPIC = "error-logs";

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

    /**
     * ECS 서비스와 런타임 연결값 구성.
     */
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
            SecurityGroup logServerSg,

            // EcrStack에서 내려오는 것
            Repository adminWebRepo,
            Repository apiServerRepo,
            Repository logServerRepo,

            // RdsStack에서 내려오는 것
            DatabaseInstance rds,
            Secret dbSecret,

            // MskStack에서 내려오는 것
            String mskClusterName,
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
        boolean kafkaEnabled = isKafkaEnabled(mskClusterName, mskClusterArn, mskBootstrapBrokersSaslIam);
        List<PolicyStatement> mskTaskPolicies = kafkaEnabled
                ? buildMskTaskPolicies(mskClusterName, mskClusterArn)
                : List.of();

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
        int analysisServerDesiredCount = kafkaEnabled
                ? Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_DESIRED_COUNT))
                : 0;
        int logServerPort = Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.LOG_SERVER_PORT));
        int logServerDesiredCount = kafkaEnabled
                ? Integer.parseInt(AppConfig.getValueOrDefault(EnvKey.LOG_SERVER_DESIRED_COUNT))
                : 0;
        Map<String, String> recommendationRealtimeEnvironment = buildRecommendationRealtimeEnvironment(
                recommendationRealtimePort,
                adminApiPort,
                mskBootstrapBrokersSaslIam
        );
        Map<String, String> analysisServerEnvironment = buildAnalysisServerEnvironment(
                analysisServerPort,
                adminApiPort,
                mskBootstrapBrokersSaslIam
        );
        Map<String, String> logServerEnvironment = buildLogServerEnvironment(
                dbUrl,
                logServerPort,
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
                kafkaEnabled ? recommendationRealtimeHotfixEntryPoint() : List.of(),
                kafkaEnabled ? recommendationRealtimeHotfixCommand() : List.of(),
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
                List.of(),
                analysisServerPort,
                recommendationRealtimeRuntimeSecret,
                buildAnalysisServerRuntimeSecretMapping(),
                serviceNs,
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_CLOUD_MAP_NAME),
                List.of(),
                mskTaskPolicies
        );

        FargateBackgroundServiceProps logServerServiceProps = new FargateBackgroundServiceProps(
                this,
                LOG_SERVER_ID,
                cluster,
                logServerRepo,
                AppConfig.getValueOrDefault(EnvKey.LOG_SERVER_IMAGE_TAG),
                logServerSg,
                ecsLogGroup,
                LOG_SERVER_LOG_STREAM_PREFIX,
                privateSubnets,
                AppConfig.getValueOrDefault(EnvKey.LOG_SERVER_SERVICE_NAME),
                512,
                1024,
                logServerDesiredCount,
                true,
                logServerEnvironment,
                List.of(),
                List.of(),
                logServerPort,
                dbSecret,
                Map.of(
                        "DB_USERNAME", "username",
                        "DB_PASSWORD", "password"
                ),
                null,
                null,
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
        this.logServerService = new FargateBackgroundService(logServerServiceProps);
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

    public FargateBackgroundService getLogServerService() {
        return logServerService;
    }

    /**
     * admin-api Kafka 연동 환경값 구성.
     */
    private Map<String, String> buildAdminApiEnvironment(String mskBootstrapBrokersSaslIam) {
        Map<String, String> env = new HashMap<>();
        // DB 풀 상한
        env.put("DB_POOL_MAX", "10");
        // DB 풀 하한
        env.put("DB_POOL_MIN", "1");
        if (hasText(mskBootstrapBrokersSaslIam)) {
            // Kafka 시크릿 이름
            env.put("KAFKA_SECRET_NAME", AppConfig.getValueOrDefault(EnvKey.API_SERVER_KAFKA_SECRET_NAME));
            // api-server가 secret import 실패 시 localhost 기본값으로 떨어지지 않도록
            // Kafka 연결값은 컨테이너 env로도 직접 주입한다.
            env.put("MSK_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
            // SASL 프로토콜
            env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
            // SASL 메커니즘
            env.put("KAFKA_SASL_MECHANISM", "AWS_MSK_IAM");
            // IAM 로그인 모듈
            env.put("KAFKA_SASL_JAAS_CONFIG", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            // IAM 콜백 핸들러
            env.put("KAFKA_SASL_CALLBACK_HANDLER_CLASS", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        // admin-api는 private recommendation service를 내부 DNS로 호출한다.
        env.put(
                "FASTAPI_BASE_URL",
                "http://" + AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_CLOUD_MAP_NAME) + "."
                        + AppConfig.getInternalDomainName() + ":"
                        + AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_PORT)
        );
        return env;
    }

    /**
     * customer-api producer 환경값 구성.
     */
    private Map<String, String> buildCustomerApiEnvironment(String mskBootstrapBrokersSaslIam) {
        Map<String, String> env = new HashMap<>();
        // DB 풀 상한
        env.put("DB_POOL_MAX", "10");
        // DB 풀 하한
        env.put("DB_POOL_MIN", "1");
        if (hasText(mskBootstrapBrokersSaslIam)) {
            // Kafka 시크릿 이름
            env.put("KAFKA_SECRET_NAME", AppConfig.getValueOrDefault(EnvKey.API_SERVER_KAFKA_SECRET_NAME));
            // IAM 브로커 주소
            env.put("MSK_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
            // SASL 프로토콜
            env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
            // SASL 메커니즘
            env.put("KAFKA_SASL_MECHANISM", "AWS_MSK_IAM");
            // IAM 로그인 모듈
            env.put("KAFKA_SASL_JAAS_CONFIG", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            // IAM 콜백 핸들러
            env.put("KAFKA_SASL_CALLBACK_HANDLER_CLASS", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
            // 클릭 로그 토픽 이름
            env.put("KAFKA_CLICK_LOG_TOPIC", AppConfig.getValueOrDefault(EnvKey.CLICK_LOG_TOPIC));
        }
        // customer-api도 recommendation 호출을 intelligence-server 단일 런타임으로 보낸다.
        env.put(
                "FASTAPI_BASE_URL",
                "http://" + AppConfig.getValueOrDefault(EnvKey.INTELLIGENCE_SERVER_CLOUD_MAP_NAME) + "."
                        + AppConfig.getInternalDomainName() + ":"
                        + AppConfig.getValueOrDefault(EnvKey.INTELLIGENCE_SERVER_PORT)
        );
        return env;
    }

    /**
     * realtime 서비스 환경값 구성.
     */
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
        if (hasText(mskBootstrapBrokersSaslIam)) {
            // realtime 모드도 추천 결과를 Kafka에 발행하므로 bootstrap/config 기본값으로 떨어지지 않게 명시한다.
            env.put("KAFKA_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
            env.put("MSK_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
            env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
            env.put("KAFKA_SASL_MECHANISM", "OAUTHBEARER");
            env.put("KAFKA_AWS_REGION", AppConfig.getRegion());
            env.put(
                    "KAFKA_RECOMMENDATION_TOPIC",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_RECOMMENDATION_TOPIC)
            );
        }

        env.put("POSTGRES_POOL_MIN_SIZE", "1");
        env.put("POSTGRES_POOL_MAX_SIZE", "2");
        env.put(
                "OPENAI_CHAT_MODEL",
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_OPENAI_CHAT_MODEL)
        );
        env.put(
                "OPENAI_EMBEDDING_MODEL",
                AppConfig.getValueOrDefault(EnvKey.RECOMMENDATION_REALTIME_OPENAI_EMBEDDING_MODEL)
        );
        env.put(
                "ADMIN_API_BASE_URL",
                "http://" + ADMIN_CLOUD_MAP_NAME + "." + AppConfig.getInternalDomainName() + ":" + adminApiPort
        );
        return env;
    }

    /**
     * 배포된 recommendation image에 producer IAM 옵션 반영 전까지 startup patch를 적용한다.
     */
    private List<String> recommendationRealtimeHotfixEntryPoint() {
        return List.of("/bin/sh", "-lc");
    }

    /**
     * recommendation producer가 localhost 기본값으로 떨어지지 않도록 컨테이너 시작 시 patch한다.
     */
    private List<String> recommendationRealtimeHotfixCommand() {
        return List.of("""
                echo '[hotfix] patching recommendation_service.py for MSK IAM auth'
                python - <<'PY'
                from pathlib import Path
                import re

                p = Path("/app/app/services/recommendation_service.py")
                s = p.read_text()
                imp_old = "from aiokafka import AIOKafkaProducer\\n"
                imp_new = "from aiokafka import AIOKafkaProducer\\n\\nfrom app.infra.kafka.client_options import build_kafka_client_options\\n"
                pattern = re.compile(
                    r"    producer = AIOKafkaProducer\\(\\n"
                    r"(?:        bootstrap_servers=\\[s\\.strip\\(\\) for s in bootstrap\\.split\\(\",\"\\) if s\\.strip\\(\\)\\],\\n)?"
                    r"        value_serializer=lambda v: json\\.dumps\\(v, ensure_ascii=False\\)\\.encode\\(\"utf-8\"\\),\\n"
                    r"    \\)\\n",
                    re.MULTILINE,
                )
                replacement = (
                    "    producer = AIOKafkaProducer(\\n"
                    "        value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode(\\"utf-8\\"),\\n"
                    "        **build_kafka_client_options(settings),\\n"
                    "    )\\n"
                )

                if "from app.infra.kafka.client_options import build_kafka_client_options" not in s:
                    s = s.replace(imp_old, imp_new, 1)

                s, count = pattern.subn(replacement, s, count=1)

                if count == 0 and replacement not in s:
                    raise SystemExit("producer block not found")

                p.write_text(s)
                PY
                exec /app/docker-entrypoint.sh
                """.strip());
    }

    /**
     * analysis-server consumer 환경값 구성.
     */
    private Map<String, String> buildAnalysisServerEnvironment(
            int analysisServerPort,
            int adminApiPort,
            String mskBootstrapBrokersSaslIam
    ) {
        Map<String, String> env = new HashMap<>();
        env.put("APP_MODE", "analysis-server");
        env.put("APP_ENV", "prod");
        env.put("DEBUG", "false");
        env.put("APP_PORT", String.valueOf(analysisServerPort));
        env.put("PYTHONUNBUFFERED", "1");
        env.put(
                "ADMIN_API_BASE_URL",
                "http://" + ADMIN_CLOUD_MAP_NAME + "." + AppConfig.getInternalDomainName() + ":" + adminApiPort
        );

        // consumer 활성값
        env.put(
                "KAFKA_CONSUMER_ENABLED",
                hasText(mskBootstrapBrokersSaslIam)
                        ? AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_CONSUMER_ENABLED)
                        : "false"
        );
        if (hasText(mskBootstrapBrokersSaslIam)) {
            // IAM 브로커 주소
            env.put("KAFKA_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
            // SASL 프로토콜
            env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
            // SASL 메커니즘
            env.put("KAFKA_SASL_MECHANISM", "OAUTHBEARER");
            // AWS 리전 값
            env.put("KAFKA_AWS_REGION", AppConfig.getRegion());
            // 분석 요청 토픽
            env.put(
                    "KAFKA_ANALYSIS_REQUEST_TOPIC",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_ANALYSIS_REQUEST_TOPIC)
            );
            // 분석 응답 토픽
            env.put(
                    "KAFKA_ANALYSIS_RESPONSE_TOPIC",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_ANALYSIS_RESPONSE_TOPIC)
            );
            // 분석 그룹 이름
            env.put(
                    "KAFKA_CONSUMER_GROUP_ID",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_CONSUMER_GROUP_ID)
            );
            // offset 초기 정책
            env.put(
                    "KAFKA_AUTO_OFFSET_RESET",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_AUTO_OFFSET_RESET)
            );
            // poll interval 상한
            env.put(
                    "KAFKA_MAX_POLL_INTERVAL_MS",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_MAX_POLL_INTERVAL_MS)
            );
            // 세션 타임아웃
            env.put(
                    "KAFKA_SESSION_TIMEOUT_MS",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_SESSION_TIMEOUT_MS)
            );
            // heartbeat 간격
            env.put(
                    "KAFKA_HEARTBEAT_INTERVAL_MS",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_HEARTBEAT_INTERVAL_MS)
            );
            // 배치 크기
            env.put(
                    "KAFKA_BATCH_SIZE",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_BATCH_SIZE)
            );
            // poll 대기 시간
            env.put(
                    "KAFKA_POLL_TIMEOUT_MS",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_POLL_TIMEOUT_MS)
            );
            // 메시지 로그 여부
            env.put(
                    "KAFKA_LOG_EACH_MESSAGE",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_LOG_EACH_MESSAGE)
            );
            // 결과 로그 상한
            env.put(
                    "KAFKA_LOG_RESULT_LIMIT",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_LOG_RESULT_LIMIT)
            );
            // 응답 재시도 횟수
            env.put(
                    "KAFKA_RESPONSE_MAX_ATTEMPTS",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_RESPONSE_MAX_ATTEMPTS)
            );
            // 추천 결과 발행 토픽
            env.put(
                    "KAFKA_RECOMMENDATION_TOPIC",
                    AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_KAFKA_RECOMMENDATION_TOPIC)
            );
        }
        // OpenAI 채팅 모델
        env.put(
                "OPENAI_CHAT_MODEL",
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_OPENAI_CHAT_MODEL)
        );
        // 임베딩 모델
        env.put(
                "OPENAI_EMBEDDING_MODEL",
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_OPENAI_EMBEDDING_MODEL)
        );
        // 추천 개수 상한
        env.put(
                "RECOMMEND_TOP_K",
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_RECOMMEND_TOP_K)
        );
        // 캐시 TTL
        env.put(
                "CACHE_TTL_DAYS",
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_CACHE_TTL_DAYS)
        );

        // DB 풀 하한
        env.put(
                "POSTGRES_POOL_MIN_SIZE",
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_POSTGRES_POOL_MIN_SIZE)
        );
        // DB 풀 상한
        env.put(
                "POSTGRES_POOL_MAX_SIZE",
                AppConfig.getValueOrDefault(EnvKey.ANALYSIS_SERVER_POSTGRES_POOL_MAX_SIZE)
        );
        return env;
    }

    /**
     * log-server consumer 환경값 구성.
     */
    private Map<String, String> buildLogServerEnvironment(
            String dbUrl,
            int logServerPort,
            String mskBootstrapBrokersSaslIam
    ) {
        Map<String, String> env = new HashMap<>();
        // 앱 이름
        env.put("SPRING_APPLICATION_NAME", "log-server");
        // 프로파일 값
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        // 서버 포트
        env.put("SERVER_PORT", String.valueOf(logServerPort));
        // DB URL 값
        env.put("DB_URL", dbUrl);
        // DDL 정책
        env.put("JPA_DDL_AUTO", "validate");
        if (hasText(mskBootstrapBrokersSaslIam)) {
            // 브로커 주소
            env.put("KAFKA_BOOTSTRAP_SERVERS", mskBootstrapBrokersSaslIam);
            // 보안 프로토콜
            env.put("KAFKA_SECURITY_PROTOCOL", "SASL_SSL");
            // SASL 메커니즘
            env.put("KAFKA_SASL_MECHANISM", "AWS_MSK_IAM");
            // IAM JAAS 설정
            env.put("KAFKA_SASL_JAAS_CONFIG", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            // IAM 콜백 핸들러
            env.put("KAFKA_SASL_CALLBACK_HANDLER_CLASS", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
            // 원본 로그 토픽
            env.put("KAFKA_TOPIC_CLIENT_EVENTS", AppConfig.getValueOrDefault(EnvKey.CLICK_LOG_TOPIC));
            // DLQ 토픽
            env.put("KAFKA_TOPIC_ERROR", ERROR_LOG_TOPIC);
            // speed group 값
            env.put("KAFKA_GROUP_SPEED", AppConfig.getValueOrDefault(EnvKey.CLICK_LOG_CONSUMER_GROUP_ID));
            // poll 크기
            env.put("KAFKA_MAX_POLL_RECORDS", "1");
            // DLQ acks 값
            env.put("KAFKA_DLQ_ACKS", "all");
            // DLQ retry 값
            env.put("KAFKA_DLQ_RETRIES", "3");
        }
        return env;
    }

    private Map<String, String> buildRecommendationRuntimeSecretMapping() {
        Map<String, String> secretJsonKeyByEnvName = new LinkedHashMap<>();
        secretJsonKeyByEnvName.put("POSTGRES_USER", "POSTGRES_USER");
        secretJsonKeyByEnvName.put("POSTGRES_HOST", "POSTGRES_HOST");
        secretJsonKeyByEnvName.put("POSTGRES_PASSWORD", "POSTGRES_PASSWORD");
        secretJsonKeyByEnvName.put("POSTGRES_PORT", "POSTGRES_PORT");
        secretJsonKeyByEnvName.put("POSTGRES_DB", "POSTGRES_DB");
        secretJsonKeyByEnvName.put("OPENAI_API_KEY", "OPENAI_API_KEY");
        return secretJsonKeyByEnvName;
    }

    private Map<String, String> buildAnalysisServerRuntimeSecretMapping() {
        Map<String, String> secretJsonKeyByEnvName = new LinkedHashMap<>();
        secretJsonKeyByEnvName.put("POSTGRES_USER", "POSTGRES_USER");
        secretJsonKeyByEnvName.put("POSTGRES_DSN", "POSTGRES_DSN");
        secretJsonKeyByEnvName.put("POSTGRES_HOST", "POSTGRES_HOST");
        secretJsonKeyByEnvName.put("POSTGRES_PASSWORD", "POSTGRES_PASSWORD");
        secretJsonKeyByEnvName.put("POSTGRES_PORT", "POSTGRES_PORT");
        secretJsonKeyByEnvName.put("POSTGRES_DB", "POSTGRES_DB");
        secretJsonKeyByEnvName.put("OPENAI_API_KEY", "OPENAI_API_KEY");
        return secretJsonKeyByEnvName;
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
        if (!hasText(value)) {
            return;
        }
        String trimmed = value.trim();
        if (!target.contains(trimmed)) {
            target.add(trimmed);
        }
    }

    private boolean isKafkaEnabled(String mskClusterName, String mskClusterArn, String mskBootstrapBrokersSaslIam) {
        return hasText(mskClusterName) && hasText(mskClusterArn) && hasText(mskBootstrapBrokersSaslIam);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
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

    private List<PolicyStatement> buildMskTaskPolicies(String mskClusterName, String mskClusterArn) {
        // Stack이 가진 region/account 컨텍스트를 사용해야 CDK 테스트에서도 외부 환경변수에 의존하지 않는다.
        String region = this.getRegion();
        String accountId = this.getAccount();

        String topicArnPattern = String.format(
                "arn:aws:kafka:%s:%s:topic/%s/*",
                region,
                accountId,
                mskClusterName
        );
        String groupArnPattern = String.format(
                "arn:aws:kafka:%s:%s:group/%s/*",
                region,
                accountId,
                mskClusterName
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
