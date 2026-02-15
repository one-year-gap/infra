package com.myorg.config;

public final class PortConfig {
    public PortConfig(){}

    /**
     * 고객용 웹 port 반환
     * @return port
     */
    public static Integer getCustomerWebPort(){
        return Integer.parseInt(System.getenv("CUSTOMER_WEB_PORT"));
    }

    /**
     * 관리자용 웹 port 반환
     * @return port
     */
    public static Integer getAdminWebPort(){
        return Integer.parseInt(System.getenv("ADMIN_WEB_PORT"));
    }

    /**
     * 고객영 서버 port 반환
     * @return port
     */
    public static Integer getCustomerServerPort(){
        return Integer.parseInt(System.getenv("CUSTOMER_SERVER_PORT"));
    }

    /**
     * 관리자용 서버 port 반환
     * @return port
     */
    public static Integer getAdminServerPort(){
        return Integer.parseInt(System.getenv("ADMIN_SERVER_PORT"));
    }
}
