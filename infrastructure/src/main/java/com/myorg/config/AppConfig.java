package com.myorg.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.Arrays;
import java.util.List;

public final class AppConfig {
    public AppConfig() {
    }
    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();

    /**
     * 가용 영역 반환
     *
     * @return 가용 영역 string
     */
    public static String getRegion() {
        return getValue("REGION");
    }

    /**
     * 허용 IP 리스트 반환
     *
     * @return 허용 IP List
     */
    public static List<String> getAdminAllowedCidrs() {
        String ipList = getValue("ADMIN_ALLOWED_CIDRS");
        if (ipList == null || ipList.isBlank()) {
            return List.of();
        }

        return Arrays.stream(ipList.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static String getVervelIp() {
        return getValue("VERCEL_APEX_IP");
    }

    /**
     * Route53 도메인 이름 반환
     * 우선순위: DOMAIN_NAME -> ROUTE53_DOMAIN_NAME -> ROOT_DOMAIN -> domainName
     */
    public static String getDomainName() {
        return getValue("DOMAIN_NAME");
    }


    private static String getValue(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        v = DOTENV.get(key);

        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
