package com.myorg.config;

public enum EnvKey {

    /*
     * =================================================================
     *                             Network
     * =================================================================
     */
    ADMIN_ALLOWED_CIDRS,
    DOMAIN_NAME,
    VERCEL_APEX_IP,
    DOMAIN_INTERNAL_NAME,

    /*
     * =================================================================
     *                              Port
     * =================================================================
     */
    ADMIN_WEB_PORT,
    CUSTOMER_WEB_PORT,
    ADMIN_SERVER_PORT,
    CUSTOMER_SERVER_PORT,

    /*
     * =================================================================
     *                              Repository
     * =================================================================
     */
    ADMIN_WEB_REPOSITORY,
    API_SERVER_REPOSITORY,

    /*
     * =================================================================
     *                              Deploy
     * =================================================================
     */
    DEPLOY_MODE("ecs"),

    /*
     * =================================================================
     *                              AWS
     * =================================================================
     */
    CDK_DEFAULT_ACCOUNT,
    REGION,
    CUSTOMER_CERT_ARN,
    ADMIN_CERT_ARN,

    /*
     * =================================================================
     *                              Tag
     * =================================================================
     */
    API_IMAGE_TAG,
    ADMIN_WEB_IMAGE_TAG,
    ADMIN_API_IMAGE_TAG,
    CUSTOMER_API_IMAGE_TAG,

    /*
     * =================================================================
     *                              Secret Manager
     * =================================================================
     */
    ADMIN_WEB_RUNTIME_SECRET_NAME,
    ADMIN_API_RUNTIME_SECRET_NAME,
    CUSTOMER_API_RUNTIME_SECRET_NAME,
    WORKER_RUNTIME_SECRET_NAME,

    ADMIN_API_RUNTIME_SECRET_ARN,
    ADMIN_API_SECRETS_MANAGER_ARNS,

    CUSTOMER_API_RUNTIME_SECRET_ARN,
    CUSTOMER_API_SECRETS_MANAGER_ARNS,

    ADMIN_API_RUNTIME_SECRET_KMS_KEY_ARN,
    ADMIN_API_RUNTIME_SECRET_KMS_KEY_ARNS,
    CUSTOMER_API_RUNTIME_SECRET_KMS_KEY_ARN,
    CUSTOMER_API_RUNTIME_SECRET_KMS_KEY_ARNS
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
