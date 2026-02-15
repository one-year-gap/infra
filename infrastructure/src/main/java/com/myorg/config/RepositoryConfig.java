package com.myorg.config;

public class RepositoryConfig {
    private RepositoryConfig() {
    }

    /**
     * 관리자용 웹 레포지토리 주소 반환
     *
     * @return admin-fe repository
     */
    public static String getAdminWebRepository() {
        String repo = System.getenv("ADMIN_WEB_REPOSITORY");

        if (repo == null || repo.isBlank()) {
            throw new IllegalStateException("환경 변수 ADMIN_WEB_REPOSITORY이 존재하지 않습니다.");
        }

        return repo;
    }

    /**
     * API 서버 레포지토리 주소 변환
     *
     * @return api-server repository
     */
    public static String getApiServerRepository() {
        String repo= System.getenv("API_SERVER_REPOSITORY");

        if (repo==null || repo.isBlank()){
            throw new IllegalStateException("환경 변수 API_SERVER_REPOSITORY이 존재하지 않습니다.");
        }

        return repo;
    }

}
