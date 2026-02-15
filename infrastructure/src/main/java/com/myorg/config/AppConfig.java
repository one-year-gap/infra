package com.myorg.config;

import java.util.Arrays;
import java.util.List;

public final class AppConfig {
    public AppConfig(){}

    /**
     * 가용 영역 반환
     * @return 가용 영역 string
     */
    public static String getRegion(){
        return System.getenv("REGION");
    }

    /**
     * 허용 IP 리스트 반환
     * @return 허용 IP List
     */
    public static List<String> getAdminAllowedCidrs(){
        String ipList = System.getenv("ADMIN_ALLOWED_CIDRS");

        return Arrays.stream(ipList.split(","))
                .map(String::trim)
                .filter(s->!s.isBlank())
                .toList();
    }
}
