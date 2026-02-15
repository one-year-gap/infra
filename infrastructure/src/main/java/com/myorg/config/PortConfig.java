package com.myorg.config;

public final class PortConfig {
    /**
 * Public no-argument constructor for PortConfig.
 *
 * <p>PortConfig is a static utility holder for retrieving port values from environment
 * variables; creating an instance is unnecessary and has no effect.
 */
public PortConfig(){}

    /**
     * Retrieve the customer-facing web port from the CUSTOMER_WEB_PORT environment variable.
     *
     * @return the port number parsed from the CUSTOMER_WEB_PORT environment variable
     */
    public static Integer getCustomerWebPort(){
        return Integer.parseInt(System.getenv("CUSTOMER_WEB_PORT"));
    }

    /**
     * Retrieve the admin web port configured via the ADMIN_WEB_PORT environment variable.
     *
     * @return the admin web port as an Integer
     */
    public static Integer getAdminWebPort(){
        return Integer.parseInt(System.getenv("ADMIN_WEB_PORT"));
    }

    /**
     * Retrieve the customer server port from the CUSTOMER_SERVER_PORT environment variable.
     *
     * @return the port number from CUSTOMER_SERVER_PORT
     */
    public static Integer getCustomerServerPort(){
        return Integer.parseInt(System.getenv("CUSTOMER_SERVER_PORT"));
    }

    /**
     * Retrieve the admin server port from the ADMIN_SERVER_PORT environment variable.
     *
     * @return the port number parsed from the ADMIN_SERVER_PORT environment variable
     */
    public static Integer getAdminServerPort(){
        return Integer.parseInt(System.getenv("ADMIN_SERVER_PORT"));
    }
}