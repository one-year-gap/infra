package com.myorg.config;

public class RepositoryConfig {
    private RepositoryConfig(){}

    /**
     * 관리자용 웹 레포지토리 주소 반환
     * @return admin-fe repository
     */
    public static String getAdminWebRepository(){
        return System.getenv("ADMIN_WEB_REPOSITORY");
    }

    /**
     * API 서버 레포지토리 주소 변환
     * @return api-server repository
     */
    public static String getApiServerRepository(){
        return System.getenv("API_SERVER_REPOSITORY");
    }

}
