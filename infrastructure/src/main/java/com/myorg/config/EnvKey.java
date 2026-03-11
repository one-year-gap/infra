package com.myorg.config;

public enum EnvKey {

    /*
     * =================================================================
     * Network
     * =================================================================
     */
    ADMIN_ALLOWED_CIDRS,
    DOMAIN_NAME,
    VERCEL_APEX_IP,
    DOMAIN_INTERNAL_NAME,

    /*
     * =================================================================
     * Port
     * =================================================================
     */
    ADMIN_WEB_PORT,
    CUSTOMER_WEB_PORT,
    ADMIN_SERVER_PORT,
    CUSTOMER_SERVER_PORT,

    /*
     * =================================================================
     * Repository
     * =================================================================
     */
    ADMIN_WEB_REPOSITORY,
    API_SERVER_REPOSITORY,
    RECOMMENDATION_REALTIME_REPOSITORY("one-year-gap/counseling-analytics"),

    /*
     * =================================================================
     * Deploy
     * =================================================================
     */
    DEPLOY_MODE("ecs"),

    /*
     * =================================================================
     * MSK
     * =================================================================
     */
    MSK_CLUSTER_NAME("holliverse-msk"),
    MSK_KAFKA_VERSION("3.8.x"),
    MSK_BROKER_INSTANCE_TYPE("kafka.t3.small"),
    MSK_BROKER_NODES("4"),
    MSK_BROKER_VOLUME_GIB("10"),
    CLICK_LOG_TOPIC("click.logs.raw.v1"),
    CLICK_LOG_CONSUMER_GROUP_ID("click-log-consumer"),

    /*
     * =================================================================
     * On-Demand Workflow
     * =================================================================
     */
    ON_DEMAND_STATE_MACHINE_NAME,
    ON_DEMAND_WORKFLOW_TIMEOUT_MINUTES,
    ON_DEMAND_SCHEDULE_EXPRESSION,
    ON_DEMAND_LOCK_KEY,
    ON_DEMAND_LOCK_TABLE_NAME,
    ON_DEMAND_LOCK_TABLE_ARN,

    ON_DEMAND_CLUSTER_ARN,
    ON_DEMAND_CLUSTER_NAME,

    ON_DEMAND_WORKER_TASK_DEFINITION_ARN,
    ON_DEMAND_WORKER_TASK_DEFINITION_FAMILY,
    ON_DEMAND_WORKER_CONTAINER_NAME,
    ON_DEMAND_WORKER_TASK_ROLE_ARN,
    ON_DEMAND_WORKER_EXECUTION_ROLE_ARN,
    ON_DEMAND_WORKER_BATCH_JOB_NAME("consultation-keyword-analysis-job"),
    ON_DEMAND_WORKER_SPRING_PROFILE("prod"),
    ON_DEMAND_WORKER_MSK_BOOTSTRAP_SERVERS,
    ON_DEMAND_WORKER_SUBNET_IDS,
    ON_DEMAND_WORKER_SECURITY_GROUP_IDS,
    ON_DEMAND_WORKER_RUN_WINDOW,
    ON_DEMAND_WORKER_POLL_SECONDS,
    ON_DEMAND_WORKER_MAX_ATTEMPTS,
    ON_DEMAND_WORKER_INPUT_BASE_PATH,
    ON_DEMAND_WORKER_OUTPUT_BASE_PATH,
    ON_DEMAND_WORKER_LOCK_BASE_PATH,

    ON_DEMAND_ANALYSIS_SERVER_BASE_URL,
    ON_DEMAND_ANALYSIS_SERVER_READY_URL,
    ON_DEMAND_ANALYSIS_SERVER_HEALTH_URL,
    ON_DEMAND_ANALYSIS_SERVER_REQUIRED_READY_CHECKS,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_VPC_ID,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_SUBNET_IDS,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_SECURITY_GROUP_IDS,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_TIMEOUT_SECONDS,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_INTERVAL_SECONDS,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_MAX_ATTEMPTS,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_LAMBDA_ASSET_PATH,
    ON_DEMAND_ANALYSIS_SERVER_PROBE_MEMORY_MB,
    ON_DEMAND_ANALYSIS_SERVER_SCALE_UP_DESIRED_COUNT,
    ON_DEMAND_ANALYSIS_SERVER_SCALE_DOWN_DESIRED_COUNT,

    ON_DEMAND_EXPECTED_RELEASE_TAG,

    ON_DEMAND_ENABLE_BUSINESS_VALIDATION,
    ON_DEMAND_BUSINESS_MIN_PROCESSED_COUNT,
    ON_DEMAND_BUSINESS_REQUIRED_RESULT_FILES,

    ON_DEMAND_BUSINESS_VALIDATOR_LAMBDA_ASSET_PATH,
    ON_DEMAND_BUSINESS_VALIDATOR_MEMORY_MB,
    ON_DEMAND_ALARM_TOPIC_ARN,

    /*
     * =================================================================
     * AWS
     * =================================================================
     */
    CDK_DEFAULT_ACCOUNT,
    REGION,
    CUSTOMER_CERT_ARN,
    ADMIN_CERT_ARN,

    /*
     * =================================================================
     * Tag
     * =================================================================
     */
    API_IMAGE_TAG,
    ADMIN_WEB_IMAGE_TAG,
    ADMIN_API_IMAGE_TAG,
    CUSTOMER_API_IMAGE_TAG,
    RECOMMENDATION_REALTIME_IMAGE_TAG("counseling-analytics-v0.0.4"),

    /*
     * =================================================================
     * Recommendation Realtime
     * =================================================================
     */
    RECOMMENDATION_REALTIME_PORT("8000"),
    RECOMMENDATION_REALTIME_DESIRED_COUNT("1"),
    RECOMMENDATION_REALTIME_RUNTIME_SECRET_NAME("holliverse/prod/counseling-analytics/runtime"),
    RECOMMENDATION_REALTIME_CLOUD_MAP_NAME("recommendation-realtime"),
    ANALYSIS_SERVER_PORT("8000"),
    ANALYSIS_SERVER_DESIRED_COUNT("0"),
    ANALYSIS_SERVER_CLOUD_MAP_NAME("analysis-server"),
    ANALYSIS_SERVER_SERVICE_NAME("analysis-server"),

    /*
     * =================================================================
     * Secret Manager
     * =================================================================
     */
    ADMIN_WEB_RUNTIME_SECRET_NAME,
    ADMIN_API_RUNTIME_SECRET_NAME,
    CUSTOMER_API_RUNTIME_SECRET_NAME,
    WORKER_RUNTIME_SECRET_NAME,
    API_SERVER_KAFKA_SECRET_NAME("holliverse/api-server/kafka"),

    ADMIN_API_RUNTIME_SECRET_ARN,
    ADMIN_API_SECRETS_MANAGER_ARNS,

    CUSTOMER_API_RUNTIME_SECRET_ARN,
    CUSTOMER_API_SECRETS_MANAGER_ARNS,

    ADMIN_API_RUNTIME_SECRET_KMS_KEY_ARN,
    ADMIN_API_RUNTIME_SECRET_KMS_KEY_ARNS,
    CUSTOMER_API_RUNTIME_SECRET_KMS_KEY_ARN,
    CUSTOMER_API_RUNTIME_SECRET_KMS_KEY_ARNS,

    /*
     * =================================================================
     * Grafana
     * =================================================================
     */
    MONITORING_INSTANCE_TYPE("t3.small"),
    MONITORING_ROOT_VOLUME_GIB("30"),
    MONITORING_SWAP_SIZE_GIB("4"),
    MONITORING_SUBNET_TYPE("PRIVATE_WITH_EGRESS"),
    MONITORING_SSM_PORT_FORWARD_DOCUMENT("AWS-StartPortForwardingSession"),
    MONITORING_DB_SECRET_NAME_PREFIX("holliverse/rds/postgres"),
    MONITORING_DB_SECRET_ID("holliverse/rds/postgres"),
    MONITORING_DOCKER_NETWORK_NAME("monitoring-net"),
    MONITORING_PROMETHEUS_CONTAINER_NAME("prometheus"),
    MONITORING_PROMETHEUS_IMAGE(
            "prom/prometheus@sha256:1f0f50f06acaceb0f5670d2c8a658a599affe7b0d8e78b898c1035653849a702"),
    MONITORING_PROMETHEUS_PORT("9090"),
    MONITORING_PROMETHEUS_SCRAPE_INTERVAL("15s"),
    MONITORING_AUTO_DASHBOARD_PANEL_LIMIT("60"),

    // Loki / Alloy
    MONITORING_LOKI_CONTAINER_NAME("loki"),
    MONITORING_LOKI_IMAGE("grafana/loki:3.6.5"),
    MONITORING_LOKI_PORT("3100"),
    MONITORING_LOKI_S3_BUCKET("hsc-monitoring"),
    MONITORING_LOKI_S3_PREFIX("loki"),
    MONITORING_LOKI_TRACE_DEBUG_RETENTION_HOURS("72"),
    MONITORING_LOKI_WARN_RETENTION_HOURS("360"),
    MONITORING_LOKI_ERROR_RETENTION_HOURS("2160"),
    MONITORING_LOKI_FATAL_RETENTION_HOURS("4320"),
    MONITORING_ALLOY_CONTAINER_NAME("alloy"),
    MONITORING_ALLOY_IMAGE("grafana/alloy:v1.8.3"),
    MONITORING_ALLOY_ECS_LOG_GROUPS("/holliverse/ecs"),
    MONITORING_ALLOY_LOG_ENV("prod"),

    MONITORING_PG_EXPORTER_CONTAINER_NAME("pg_exporter"),
    MONITORING_PG_EXPORTER_IMAGE(
            "ghcr.io/nbari/pg_exporter@sha256:a5c6693dfd41c5bea7391e387a860252712e6ab62c2c1293fb7d972edf3a72ef"),
    MONITORING_PG_EXPORTER_PORT("9432"),
    MONITORING_PG_EXPORTER_EXCLUDE_DATABASES("rdsadmin,postgres,template0,template1"),
    MONITORING_PG_EXPORTER_JOB_NAME("pg_exporter"),
    MONITORING_ADMIN_API_JOB_NAME("admin-api"),
    MONITORING_CUSTOMER_API_JOB_NAME("customer-api"),
    MONITORING_ADMIN_API_SERVICE_DNS_LABEL("admin-api"),
    MONITORING_CUSTOMER_API_SERVICE_DNS_LABEL("customer-api"),

    // Grafana
    MONITORING_GRAFANA_PORT("3000"),
    MONITORING_LOCAL_FORWARD_PORT("13000"),
    MONITORING_GRAFANA_REPO_NAME("grafana"),
    MONITORING_GRAFANA_REPO_BASE_URL("https://rpm.grafana.com"),
    MONITORING_GRAFANA_REPO_GPG_KEY_URL("https://rpm.grafana.com/gpg.key"),
    MONITORING_GRAFANA_PACKAGE_NAME("grafana"),
    MONITORING_GRAFANA_SERVICE_NAME("grafana-server"),
    MONITORING_GRAFANA_ADMIN_USER,
    MONITORING_GRAFANA_ADMIN_PASSWORD,

    // Pinpoint
    MONITORING_PINPOINT_REPO_URL("https://github.com/pinpoint-apm/pinpoint-docker.git"),
    MONITORING_PINPOINT_REPO_DIR("/opt/pinpoint-docker"),
    MONITORING_PINPOINT_VERSION("3.0.4"),
    MONITORING_PINPOINT_GIT_REF(""),
    MONITORING_PINPOINT_WEB_PORT("8080"),
    MONITORING_PINPOINT_LOCAL_FORWARD_PORT("18080"),

    //AWS WAF
    ALB_WAF_ENABLED,
    ALB_WAF_BLOCKED_COUNTRIES,
    ALB_WAF_RATE_LIMIT_PER_5MIN,
    ALB_WAF_BLOCKED_USER_AGENT_CONTAINS,
    ALB_WAF_ATTACH_CUSTOMER,
    ALB_WAF_ATTACH_ADMIN,
    ;

    private final String defaultValue;

    EnvKey() {
        this(null);
    }

    EnvKey(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String key() {
        return this.name();
    }
}
