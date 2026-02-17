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
        return getValue("REGION");
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
        return getValue("VERCEL_APEX_IP");
    }

    /**
     * Route53 도메인 이름 반환
     */
    public static String getDomainName() {
        return getValue("DOMAIN_NAME");
    }


    private static String getValue(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        v = DOTENV.get(key);

        if (v == null || v.isBlank()) {
            throw new IllegalStateException(key + "에 해당하는 환경변수가 존재하지 않습니다.");
        }
        return v.trim();
    }
}
