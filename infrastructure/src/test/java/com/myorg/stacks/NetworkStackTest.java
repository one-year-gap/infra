package com.myorg.stacks;

import com.myorg.config.NetworkStackConfig;
import org.junit.jupiter.api.*;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;


class NetworkStackTest {
    private Template template;

    private static final List<String> allowedIpsList = List.of(
            "203.0.113.10/32",
            "123.31.100.20/32"
    );

    private static final int ADMIN_SERVER_PORT = 8080;
    private static final int ADMIN_WEB_PORT = 3001;
    private static final int CUSTOMER_SERVER_PORT = 8080;
    private static final int CUSTOMER_WEB_PORT = 3000;

    @BeforeEach
    void setUp() {
        App app = new App();

        NetworkStackConfig config = new NetworkStackConfig(
                allowedIpsList,
                ADMIN_SERVER_PORT,
                ADMIN_WEB_PORT,
                CUSTOMER_SERVER_PORT,
                CUSTOMER_WEB_PORT
        );

        NetworkStack stack = new NetworkStack(
                app, "TestNetworkStack", StackProps.builder().build(), config);

        template = Template.fromStack(stack);
    }

    @Nested
    @DisplayName("VPC 구성 Test")
    class VPCTest{
        @Test
        @DisplayName("VPC가 1개 생성된다.")
        void should_create_one_vpc(){
            template.resourceCountIs("AWS::EC2::VPC",1);
        }

        @Test
        @DisplayName("NAT Gateway가 1개 생성된다.")
        void should_create_one_nat_gateway(){
            template.resourceCountIs("AWS::EC2::NatGateway",1);
        }

        @Test
        @DisplayName("Public, Private Subnet 2개식 생성된다.")
        void should_create_two_subnet(){
            template.resourceCountIs("AWS::EC2::Subnet",4);
        }

        @Test
        @DisplayName("Internet Gateway가 연결된다.")
        void should_connect_internet_gateway(){
            template.resourceCountIs("AWS::EC2::InternetGateway",1);
        }
    }

    @Nested
    @DisplayName("Customer ALB Security Group 테스트")
    class CustomerALBTest{
        @DisplayName("HTTP(80) 인바운드는 모든 IP에서 허용한다.")
        @Test
        void should_allowed_all_http_request_anywhere(){
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupIngress",Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol","tcp",
                                    "FromPort",80,
                                    "ToPort",80,
                                    "CidrIp","0.0.0.0/0"
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("HTTPS(443) 인바운드를 모든 IP에서 허용한다")
        void shouldAllowHttpsFromAnywhere() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupIngress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", 443,
                                    "ToPort", 443,
                                    "CidrIp", "0.0.0.0/0"
                            ))
                    )))
            ));
        }
    }

    @Nested
    @DisplayName("Customer API Security Group")
    class CustomerApiSgTest{
        @Test
        @DisplayName("customer ALB Security Group에서만 8080 inbound를 허용한다.")
        void should_allow_inbound_customer_alb_only(){
            template.hasResourceProperties("AWS::EC2::SecurityGroup",Match.objectLike(
                    Map.of("SecurityGroupIngress",Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol","tcp",
                                    "FromPort",CUSTOMER_SERVER_PORT,
                                    "ToPort",CUSTOMER_SERVER_PORT
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("HTTPS(443) 아웃바운드를 허용한다")
        void shouldAllowHttpsOutbound() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupEgress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", 443,
                                    "ToPort", 443,
                                    "CidrIp", "0.0.0.0/0"
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("DNS(53) 아웃바운드를 허용한다")
        void shouldAllowDnsOutbound() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupEgress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", 53,
                                    "ToPort", 53,
                                    "CidrIp", "0.0.0.0/0"
                            ))
                    )))
            ));
        }
    }
    @Nested
    @DisplayName("Admin API Security Group")
    class AdminApiSecurityGroup{
        @Test
        @DisplayName("허가된 IP(203.0.113.10/32)만 80 인바운드를 허용한다")
        void shouldAllowInboundFromFirstAllowedCidr() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupIngress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", 80,
                                    "ToPort", 80,
                                    "CidrIp", "203.0.113.10/32"
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("허가된 IP(123.31.100.20/32)만 80 인바운드를 허용한다")
        void shouldAllowInboundFromSecondAllowedCidr() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupIngress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", 80,
                                    "ToPort", 80,
                                    "CidrIp", "123.31.100.20/32"
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("0.0.0.0/0 인바운드가 없다 — 외부 차단 확인 ★")
        void shouldNotAllowPublicInbound() {
            // Admin ALB SG에 anyIpv4 인바운드가 존재하면 안 됨
            List<Map<String, Object>> resources = template
                    .findResources("AWS::EC2::SecurityGroup").values().stream()
                    .filter(r -> {
                        Object desc = ((Map<?, ?>) ((Map<?, ?>) r).get("Properties"))
                                .get("GroupDescription");
                        return desc != null && desc.toString().contains("AdminAlbSg");
                    })
                    .map(r -> (Map<String, Object>) ((Map<?, ?>) r).get("Properties"))
                    .toList();

            resources.forEach(props -> {
                List<?> ingressRules = (List<?>) props.get("SecurityGroupIngress");
                if (ingressRules != null) {
                    ingressRules.forEach(rule -> {
                        Map<?, ?> ruleMap = (Map<?, ?>) rule;
                        assertThat(ruleMap.get("CidrIp"))
                                .as("Admin ALB SG에 0.0.0.0/0 인바운드가 존재하면 안된다")
                                .isNotEqualTo("0.0.0.0/0");
                    });
                }
            });
        }

        @Test
        @DisplayName("Admin API 포트로 아웃바운드를 허용한다")
        void shouldAllowOutboundToAdminApi() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupEgress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", ADMIN_SERVER_PORT,
                                    "ToPort", ADMIN_SERVER_PORT
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("Admin Web 포트로 아웃바운드를 허용한다")
        void shouldAllowOutboundToAdminWeb() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupEgress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", ADMIN_WEB_PORT,
                                    "ToPort", ADMIN_WEB_PORT
                            ))
                    )))
            ));
        }
    }


    @Nested
    @DisplayName("Admin Web Security Group")
    class AdminWebSgTest {

        @Test
        @DisplayName("Admin ALB에서만 adminWebPort 인바운드를 허용한다")
        void shouldAllowInboundFromAdminAlbOnly() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupIngress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", ADMIN_WEB_PORT,
                                    "ToPort", ADMIN_WEB_PORT
                            ))
                    )))
            ));
        }

        @Test
        @DisplayName("Admin API 포트로만 아웃바운드를 허용한다")
        void shouldAllowOutboundToAdminApiPortOnly() {
            // adminServerPort로 나감
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupEgress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", ADMIN_SERVER_PORT,
                                    "ToPort", ADMIN_SERVER_PORT
                            ))
                    )))
            ));
        }
    }


    @Nested
    @DisplayName("Admin API Security Group")
    class AdminApiSgTest {

        @Test
        @DisplayName("Admin ALB에서만 adminServerPort 인바운드를 허용한다 ★")
        void shouldAllowInboundFromAdminAlbOnly() {
            template.hasResourceProperties("AWS::EC2::SecurityGroup", Match.objectLike(
                    Map.of("SecurityGroupIngress", Match.arrayWith(List.of(
                            Match.objectLike(Map.of(
                                    "IpProtocol", "tcp",
                                    "FromPort", ADMIN_SERVER_PORT,
                                    "ToPort", ADMIN_SERVER_PORT
                            ))
                    )))
            ));
        }
    }

    @DisplayName("Security Group은 5개 생성된다 - CustomerAlbSg, CusteomerApiSg, AdminAlbSg, AdminWebSg, AdminApiSg")
    @Test
    void should_create_five_security_groups(){
        template.resourceCountIs("AWS::EC2::SecurityGroup",5);
    }
}
