package com.myorg.stacks;

import com.myorg.props.ApplicationLoadBalancerProps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.ICertificate;
import software.amazon.awscdk.services.ec2.Connections;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.IEcsLoadBalancerTarget;
import software.amazon.awscdk.services.ecs.LoadBalancerTargetOptions;
import software.amazon.awscdk.services.elasticloadbalancing.LoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.IpTarget;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlbStackTest {
    private Template template;

    @BeforeEach
    void setUp() {
        //given
        App app = new App();
        Stack fixtureStack = new Stack(app, "AlbFixtureStack");

        Vpc vpc = Vpc.Builder.create(fixtureStack, "TestVpc")
                .maxAzs(2)
                .build();

        SecurityGroup customerAlbSg = SecurityGroup.Builder.create(fixtureStack, "CustomerAlbSg")
                .vpc(vpc)
                .build();
        SecurityGroup adminAlbSg = SecurityGroup.Builder.create(fixtureStack, "AdminAlbSg")
                .vpc(vpc)
                .build();

        FargateService customerApiService = mock(FargateService.class);
        FargateService adminWebService = mock(FargateService.class);

        IEcsLoadBalancerTarget customerApiTarget = new TestEcsLoadBalancerTarget("10.0.10.10", 8080);
        IEcsLoadBalancerTarget adminWebTarget = new TestEcsLoadBalancerTarget("10.0.20.20", 3001);

        when(customerApiService.loadBalancerTarget(any(LoadBalancerTargetOptions.class)))
                .thenReturn(customerApiTarget);
        when(adminWebService.loadBalancerTarget(any(LoadBalancerTargetOptions.class)))
                .thenReturn(adminWebTarget);

        ICertificate customerCert = Certificate.fromCertificateArn(
                fixtureStack,
                "CustomerCert",
                "arn:aws:acm:us-east-1:111111111111:certificate/11111111-1111-1111-1111-111111111111"
        );
        ICertificate adminCert = Certificate.fromCertificateArn(
                fixtureStack,
                "AdminCert",
                "arn:aws:acm:us-east-1:111111111111:certificate/22222222-2222-2222-2222-222222222222"
        );

        ApplicationLoadBalancerProps props = new ApplicationLoadBalancerProps(
                vpc,
                customerAlbSg,
                adminAlbSg,
                customerApiService,
                8080,
                adminWebService,
                3001,
                customerCert,
                adminCert
        );

        AlbStack albStack = new AlbStack(
                app,
                "AlbStackTest",
                StackProps.builder().build(),
                props
        );

        template = Template.fromStack(albStack);
    }

    @Test
    @DisplayName("ALB 기본 리소스가 생성 한다.")
    void should_create_basic_alb_resources() {
        //when
        Map<String, Map<String, Object>> loadBalancers = template.findResources("AWS::ElasticLoadBalancingV2::LoadBalancer");
        Map<String, Map<String, Object>> listeners = template.findResources("AWS::ElasticLoadBalancingV2::Listener");
        Map<String, Map<String, Object>> targetGroups = template.findResources("AWS::ElasticLoadBalancingV2::TargetGroup");

        //then
        assertEquals(2, loadBalancers.size());
        assertEquals(4, listeners.size());
        assertEquals(2, targetGroups.size());
    }

    @Test
    @DisplayName("HTTP 리스너는 HTTPS로 리다이렉트 된다.")
    void should_redirect_http_to_https() {
        //then
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::Listener", Map.of(
                "Port", 80,
                "Protocol", "HTTP",
                "DefaultActions", List.of(
                        Map.of(
                                "Type", "redirect",
                                "RedirectConfig", Map.of(
                                        "Protocol", "HTTPS",
                                        "Port", "443",
                                        "StatusCode", "HTTP_301"
                                )
                        )
                )
        ));
    }

    @Test
    @DisplayName("TargetGroup 헬스체크 경로가 설정되어야 한다.")
    void should_configure_target_group_health_checks() {
        //then
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup", Map.of(
                "Port", 8080,
                "Protocol", ApplicationProtocol.HTTP.toString(),
                "HealthCheckPath", "/actuator/health",
                "Matcher", Map.of("HttpCode", "200")
        ));

        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup", Map.of(
                "Port", 3001,
                "Protocol", ApplicationProtocol.HTTP.toString(),
                "HealthCheckPath", "/health",
                "Matcher", Map.of("HttpCode", "200-399")
        ));
    }

    private static final class TestEcsLoadBalancerTarget extends IpTarget implements IEcsLoadBalancerTarget {
        private final Connections connections = new Connections();

        private TestEcsLoadBalancerTarget(String ipAddress, Number port) {
            super(ipAddress, port);
        }

        @Override
        public void attachToClassicLB(LoadBalancer loadBalancer) {
            // not used in this ALB test
        }

        @Override
        public Connections getConnections() {
            return connections;
        }
    }

}
