package com.myorg.stacks;

import com.myorg.config.ContainerConfig;
import com.myorg.props.ApplicationLoadBalancerProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.constructs.Construct;

import java.util.List;

public class AlbStack extends Stack {
    private final ApplicationLoadBalancer customerAlb;
    private final ApplicationLoadBalancer adminAlb;

    /**
     * Customer
     */
    private final static String CUSTOMER_ALB = "CustomerAlb";
    private final static String CUSTOMER_REDIRECT_HTTP = "CustomerHTTP";
    private final static String CUSTOMER_API_TARGET = "CustomerApiTargets";
    private final static String CUSTOMER_HEALTH_CHECK = "/actuator/health";
    private final static String CUSTOMER_REDIRECT_ACTION = "CustomerRedirectToHttps";
    private final static String CUSTOMER_HTTPS_LISTENER = "CustomerHttps";


    /**
     * Admin
     */
    private final static String ADMIN_ALB = "AdminAlb";
    private final static String ADMIN_REDIRECT_HTTP = "AdminHTTP";
    private final static String ADMIN_WEB_TARGET = "AdminWebTargets";
    private final static String ADMIN_HEALTH_CHECK = "/health";
    private final static String ADMIN_REDIRECT_ACTION = "AdminRedirectToHttps";
    private final static String ADMIN_HTTPS_LISTENER = "AdminHttps";

    public AlbStack(
            ApplicationLoadBalancerProps loadBalancerProps,
            final Construct scope,
            final String id,
            final StackProps props
    ) {
        super(scope, id, props);

        SubnetSelection publicSubnets = SubnetSelection.builder()
                .subnetType(SubnetType.PUBLIC)
                .build();

        /** ===================================================
         * Customer ALB (Public)
         * Internet -> Customer ALB -> Customer API
         * ===================================================
         */
        this.customerAlb = ApplicationLoadBalancer.Builder.create(this, CUSTOMER_ALB)
                .vpc(loadBalancerProps.vpc())
                .internetFacing(true)//인터넷 접근 가능한 public ALB - 공개 subnet 배치
                .securityGroup(loadBalancerProps.customerAlbSg())
                .vpcSubnets(publicSubnets)//ALB ENI를 어떤 subnet에 두는가
                .build();


        //80 -> 443 Listener
        ApplicationListener customerHttp = customerAlb.addListener(CUSTOMER_REDIRECT_HTTP, BaseApplicationListenerProps.builder()
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .open(false)//0.0.0.0/0 인바운드 설정 false
                .build()
        );

        customerHttp.addAction(CUSTOMER_REDIRECT_ACTION, AddApplicationActionProps.builder()
                .action(ListenerAction.redirect(RedirectOptions.builder()
                        .protocol("HTTPS")
                        .port("443")
                        .permanent(true)
                        .build()))
                //HTTP로 들어온 요청 HTTPS redirect
                .build());

        //443 listener
        ApplicationListener customerHttps = customerAlb.addListener(CUSTOMER_HTTPS_LISTENER, BaseApplicationListenerProps.builder()
                .port(443)
                .protocol(ApplicationProtocol.HTTPS)
                .certificates(List.of(
                        ListenerCertificate.fromCertificateManager(loadBalancerProps.customerCert())
                ))
                .open(false)
                .build());

        //TargetGroup -> Customer API
        customerHttps.addTargets(CUSTOMER_API_TARGET, AddApplicationTargetsProps.builder()
                //ALB -> container 전달방식: HTTP
                .protocol(ApplicationProtocol.HTTP)
                //ALB -> Container에 보내는 트래픽 포트
                .port(loadBalancerProps.customerApiPort())
                .targets(List.of(
                        loadBalancerProps.customerApiService().loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                .containerName(ContainerConfig.API_CONTAINER_NAME)
                                .containerPort(loadBalancerProps.customerApiPort())
                                .build())
                ))
                .healthCheck(HealthCheck.builder()
                        .path(CUSTOMER_HEALTH_CHECK)
                        .healthyHttpCodes("200")
                        .interval(Duration.seconds(30))
                        .timeout(Duration.seconds(5))
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(3)
                        .build())
                .build());

        /**===================================================
         * Admin ALB
         * allowed IPs -> Admin ALB => admin web
         * ===================================================
         */
        this.adminAlb = ApplicationLoadBalancer.Builder.create(this, ADMIN_ALB)
                .vpc(loadBalancerProps.vpc())
                .internetFacing(true)
                .securityGroup(loadBalancerProps.adminAlbSg())
                .vpcSubnets(publicSubnets)
                .build();

        //80 -> 443
        ApplicationListener adminHttp = adminAlb.addListener(ADMIN_REDIRECT_HTTP, BaseApplicationListenerProps.builder()
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .open(false)//0.0.0.0/0 인바운드 자동 추가하지 않도록
                .build());

        adminHttp.addAction(ADMIN_REDIRECT_ACTION, AddApplicationActionProps.builder()
                .action(ListenerAction.redirect(RedirectOptions.builder()
                        .protocol("HTTPS")
                        .port("443")
                        .permanent(true)//301 redirect
                        .build()))
                .build());

        //443 Listener
        ApplicationListener adminHttps = adminAlb.addListener(ADMIN_HTTPS_LISTENER, BaseApplicationListenerProps.builder()
                .port(443)
                .protocol(ApplicationProtocol.HTTPS)
                .certificates(List.of(ListenerCertificate.fromCertificateManager(loadBalancerProps.adminCert())))
                .open(false)//인바운드 허용은 SG에서만 관리
                .build());

        //TaragetGroup -> Admin Web
        adminHttps.addTargets(ADMIN_WEB_TARGET, AddApplicationTargetsProps.builder()
                .protocol(ApplicationProtocol.HTTP)//ALB -> Container는 HTTP로
                .port(loadBalancerProps.adminWebPort())//ALB -> Container 전달 포트
                .targets(List.of(
                        loadBalancerProps.adminWebService().loadBalancerTarget(LoadBalancerTargetOptions.builder()
                                .containerName(ContainerConfig.WEB_CONTAINER_NAME)
                                .containerPort(loadBalancerProps.adminWebPort())
                                .build())
                ))
                .healthCheck(HealthCheck.builder()
                        .path(ADMIN_HEALTH_CHECK)
                        .healthyHttpCodes("200-399")
                        .interval(Duration.seconds(30))
                        .timeout(Duration.seconds(5))
                        .healthyThresholdCount(2)
                        .unhealthyThresholdCount(3)
                        .build())
                .build());
    }

    public ApplicationLoadBalancer getCustomerAlb() {
        return customerAlb;
    }

    public ApplicationLoadBalancer getAdminAlb() {
        return adminAlb;
    }

}
