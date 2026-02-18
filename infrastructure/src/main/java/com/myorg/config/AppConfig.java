package com.myorg.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.List;

public final class AppConfig {
    private AppConfig() {
    }

    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();

    /**
     * 가용 영역 반환
     *
     * @return 가용 영역 string
     */
    public static String getRegion() {
        return getRequiredValue("REGION");
    }

    /**
     * 허용 IP 리스트 반환
     *
     * @return 허용 IP List
     */
    public static List<String> getAdminAllowedCidrs() {
        String ipList = System.getenv("ADMIN_ALLOWED_CIDRS");

        if (ipList == null || ipList.isBlank()) {
            ipList = DOTENV.get("ADMIN_ALLOWED_CIDRS");
        }

        return Arrays.stream(ipList.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static String getVercelIp() {
        return getRequiredValue("VERCEL_APEX_IP");
    }

    /**
     * Route53 도메인 이름 반환
     */
    public static String getDomainName() {
        return getRequiredValue("DOMAIN_NAME");
    }


    /**
     * domain internal 주소
     */
    public static String getInternalDomainName(){
        return getRequiredValue("DOMAIN_INTERNAL_NAME");
    }

    public static String getAccountId() {
        String fromCdkDefaultAccount = getOptionalValue("CDK_DEFAULT_ACCOUNT");
        if (fromCdkDefaultAccount != null) {
            return fromCdkDefaultAccount;
        }

        String fromAwsAccountId = getOptionalValue("AWS_ACCOUNT_ID");
        if (fromAwsAccountId != null) {
            return fromAwsAccountId;
        }

        throw new IllegalStateException("환경 변수 CDK_DEFAULT_ACCOUNT 또는 AWS_ACCOUNT_ID가 존재하지 않습니다.");
    }

    public static String getDeployMode(String defaultValue) {
        return getOptionalValueOrDefault("DEPLOY_MODE", defaultValue);
    }

    public static String getCustomerCertArn() {
        return getRequiredValue("CUSTOMER_CERT_ARN");
    }

    public static String getAdminCertArn() {
        return getRequiredValue("ADMIN_CERT_ARN");
    }

    public static String getRequiredValue(String key) {
        String value = getOptionalValue(key);
        if (value == null) {
            throw new IllegalStateException(key + "에 해당하는 환경변수가 존재하지 않습니다.");
        }
        return value;
    }

    public static String getOptionalValueOrDefault(String key, String defaultValue) {
        String value = getOptionalValue(key);
        return value != null ? value : defaultValue;
    }

    private static String getOptionalValue(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }

        value = DOTENV.get(key);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }

        return null;
    }
}
