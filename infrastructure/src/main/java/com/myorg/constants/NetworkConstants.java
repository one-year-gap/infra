package com.myorg.constants;

import com.myorg.stacks.NetworkStack;
import software.amazon.awscdk.services.ec2.Port;

public final class NetworkConstants {
    private NetworkConstants() {
    }

    /*
     * =================================================================
     *                              VPC
     * =================================================================
     */
    public static final int MAX_AZ = 2;
    public static final int NAT_GATEWAYS = 1;
    public static final int CIDR_MASK = 24;

    /*
     * =================================================================
     *                              Ports
     * =================================================================
     */
    public static final int PORT_HTTP = 80;
    public static final int PORT_HTTPS = 443;
    public static final int PORT_DNS = 53;
    public static final int PORT_POSTGRES = 5432;

    /*
     * =================================================================
     *                              Subnet
     * =================================================================
     */
    public static final String SUBNET_PUBLIC = "Public";
    public static final String SUBNET_PRIVATE = "Private";


    /*
    * =================================================================
    *                              Port Object
    * =================================================================
    */
    public static final Port HTTP = Port.tcp(PORT_HTTP);
    public static final Port HTTPS = Port.tcp(PORT_HTTPS);
    public static final Port DNS_TCP = Port.tcp(PORT_DNS);
    public static final Port DNS_UDP = Port.udp(PORT_DNS);
    public static final Port POSTGRES = Port.tcp(PORT_POSTGRES);
}
