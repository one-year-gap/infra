package com.myorg.config;

import java.util.Arrays;
import java.util.List;

public final class AppConfig {
    /**
 * Creates a new AppConfig instance.
 */
public AppConfig(){}

    /**
     * Retrieve the deployment region from the environment.
     *
     * @return the value of the "REGION" environment variable, or null if it is not set.
     */
    public static String getRegion(){
        return System.getenv("REGION");
    }

    /**
     * Parse and return the configured admin CIDR blocks from the environment.
     *
     * The environment variable `ADMIN_ALLOWED_CIDRS` is split on commas; each entry is trimmed
     * and blank entries are omitted.
     *
     * @return the list of trimmed, non-empty CIDR strings from `ADMIN_ALLOWED_CIDRS`
     * @throws NullPointerException if the `ADMIN_ALLOWED_CIDRS` environment variable is not set
     */
    public static List<String> getAdminAllowedCidrs(){
        String ipList = System.getenv("ADMIN_ALLOWED_CIDRS");

        return Arrays.stream(ipList.split(","))
                .map(String::trim)
                .filter(s->!s.isBlank())
                .toList();
    }
}