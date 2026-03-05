package com.myorg.config;

import java.util.Arrays;
import java.util.List;

/**
 * ALB WAF 운영 설정
 * - 국가 기반 차단
 * - IP 기반 rate limit 차단
 * - User-Agent 패턴 기반 차단
 */
public record WafConfig(
        boolean enabled,
        List<String> blockedCountries,
        long maxRequestsPerFiveMinutesPerIp,
        boolean attachToCustomerAlb,
        boolean attachToAdminAlb
) {

    public static WafConfig fromEnv() {
        return new WafConfig(
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ALB_WAF_ENABLED)),
                parseCsvUpper(AppConfig.getValueOrDefault(EnvKey.ALB_WAF_BLOCKED_COUNTRIES)),
                Long.parseLong(AppConfig.getValueOrDefault(EnvKey.ALB_WAF_RATE_LIMIT_PER_5MIN)),
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ALB_WAF_ATTACH_CUSTOMER)),
                Boolean.parseBoolean(AppConfig.getValueOrDefault(EnvKey.ALB_WAF_ATTACH_ADMIN))
        );
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> parseCsvUpper(String csv) {
        return parseCsv(csv).stream()
                .map(String::toUpperCase)
                .toList();
    }
}
