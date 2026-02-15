package com.myorg.stacks;

import com.myorg.config.NetworkStackConfig;
import org.junit.jupiter.api.*;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Template;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;


class NetworkStackTest {
    private Template template;
    private String customerAlbSgLogicalId;
    private String customerApiSgLogicalId;
    private String adminAlbSgLogicalId;
    private String adminWebSgLogicalId;
    private String adminApiSgLogicalId;

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

        customerAlbSgLogicalId = findSecurityGroupLogicalIdByDescription(
                "Customer Application Load Balancer Security Group");
        customerApiSgLogicalId = findSecurityGroupLogicalIdByDescription(
                "Customer API Server Security Group");
        adminAlbSgLogicalId = findSecurityGroupLogicalIdByDescription(
                "Admin Application Load Balancer Security Group");
        adminWebSgLogicalId = findSecurityGroupLogicalIdByDescription(
                "Admin Web(ECS) Security Group");
        adminApiSgLogicalId = findSecurityGroupLogicalIdByDescription(
                "Admin API Server(ECS) Security Group");
    }

    @SuppressWarnings("unchecked")
    private String findSecurityGroupLogicalIdByDescription(String description) {
        Map<String, Object> securityGroups = (Map<String, Object>) (Map<?, ?>) template
                .findResources("AWS::EC2::SecurityGroup");

        return securityGroups.entrySet().stream()
                .filter(entry -> {
                    Map<String, Object> props = resourceProperties(entry.getValue());
                    return description.equals(props.get("GroupDescription"));
                })
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("SecurityGroup not found: " + description));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourceProperties(Object resource) {
        return (Map<String, Object>) ((Map<?, ?>) resource).get("Properties");
    }

    private static String referencedLogicalId(Object token) {
        if (token instanceof String str) {
            return str;
        }
        if (!(token instanceof Map<?, ?> map)) {
            return null;
        }

        Object ref = map.get("Ref");
        if (ref != null) {
            return ref.toString();
        }

        Object getAtt = map.get("Fn::GetAtt");
        if (getAtt instanceof List<?> list && !list.isEmpty()) {
            return list.get(0).toString();
        }
        if (getAtt instanceof String str) {
            int dot = str.indexOf('.');
            return dot >= 0 ? str.substring(0, dot) : str;
        }
        return null;
    }

    private static int intProp(Map<String, Object> props, String key) {
        Object value = props.get(key);
        if (!(value instanceof Number number)) {
            throw new AssertionError("Expected number for '" + key + "' but was: " + value);
        }
        return number.intValue();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ingressRulesFor(String sgLogicalId) {
        Map<String, Object> ingress = (Map<String, Object>) (Map<?, ?>) template
                .findResources("AWS::EC2::SecurityGroupIngress");

        return ingress.values().stream()
                .map(NetworkStackTest::resourceProperties)
                .filter(props -> sgLogicalId.equals(referencedLogicalId(props.get("GroupId"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> egressRulesFor(String sgLogicalId) {
        Map<String, Object> egress = (Map<String, Object>) (Map<?, ?>) template
                .findResources("AWS::EC2::SecurityGroupEgress");

        return egress.values().stream()
                .map(NetworkStackTest::resourceProperties)
                .filter(props -> sgLogicalId.equals(referencedLogicalId(props.get("GroupId"))))
                .toList();
    }

    private void assertIngressCidrRule(String sgLogicalId, int port, String cidrIp) {
        assertThat(ingressRulesFor(sgLogicalId))
                .anySatisfy(props -> {
                    assertThat(props.get("IpProtocol")).isEqualTo("tcp");
                    assertThat(intProp(props, "FromPort")).isEqualTo(port);
                    assertThat(intProp(props, "ToPort")).isEqualTo(port);
                    assertThat(props.get("CidrIp")).isEqualTo(cidrIp);
                });
    }

    private void assertIngressFromSecurityGroup(String targetSgLogicalId, String sourceSgLogicalId, int port) {
        assertThat(ingressRulesFor(targetSgLogicalId))
                .anySatisfy(props -> {
                    assertThat(props.get("IpProtocol")).isEqualTo("tcp");
                    assertThat(intProp(props, "FromPort")).isEqualTo(port);
                    assertThat(intProp(props, "ToPort")).isEqualTo(port);
                    assertThat(referencedLogicalId(props.get("SourceSecurityGroupId")))
                            .isEqualTo(sourceSgLogicalId);
                });
    }

    private void assertEgressCidrRule(String sgLogicalId, String ipProtocol, int port, String cidrIp) {
        assertThat(egressRulesFor(sgLogicalId))
                .anySatisfy(props -> {
                    assertThat(props.get("IpProtocol")).isEqualTo(ipProtocol);
                    assertThat(intProp(props, "FromPort")).isEqualTo(port);
                    assertThat(intProp(props, "ToPort")).isEqualTo(port);
                    assertThat(props.get("CidrIp")).isEqualTo(cidrIp);
                });
    }

    private void assertEgressToSecurityGroup(String sourceSgLogicalId, String destinationSgLogicalId, int port) {
        assertThat(egressRulesFor(sourceSgLogicalId))
                .anySatisfy(props -> {
                    assertThat(props.get("IpProtocol")).isEqualTo("tcp");
                    assertThat(intProp(props, "FromPort")).isEqualTo(port);
                    assertThat(intProp(props, "ToPort")).isEqualTo(port);
                    assertThat(referencedLogicalId(props.get("DestinationSecurityGroupId")))
                            .isEqualTo(destinationSgLogicalId);
                });
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
            assertIngressCidrRule(customerAlbSgLogicalId, 80, "0.0.0.0/0");
        }

        @Test
        @DisplayName("HTTPS(443) 인바운드를 모든 IP에서 허용한다")
        void shouldAllowHttpsFromAnywhere() {
            assertIngressCidrRule(customerAlbSgLogicalId, 443, "0.0.0.0/0");
        }
    }

    @Nested
    @DisplayName("Customer API Security Group")
    class CustomerApiSgTest{
        @Test
        @DisplayName("customer ALB Security Group에서만 8080 inbound를 허용한다.")
        void should_allow_inbound_customer_alb_only(){
            assertIngressFromSecurityGroup(customerApiSgLogicalId, customerAlbSgLogicalId, CUSTOMER_SERVER_PORT);
        }

        @Test
        @DisplayName("HTTPS(443) 아웃바운드를 허용한다")
        void shouldAllowHttpsOutbound() {
            assertEgressCidrRule(customerApiSgLogicalId, "tcp", 443, "0.0.0.0/0");
        }

        @Test
        @DisplayName("DNS(53) 아웃바운드를 허용한다")
        void shouldAllowDnsOutbound() {
            assertEgressCidrRule(customerApiSgLogicalId, "tcp", 53, "0.0.0.0/0");
        }
    }
    @Nested
    @DisplayName("Admin ALB Security Group")
    class AdminAlbSgTest{
        @Test
        @DisplayName("허가된 IP(203.0.113.10/32)만 80 인바운드를 허용한다")
        void shouldAllowInboundFromFirstAllowedCidr() {
            assertIngressCidrRule(adminAlbSgLogicalId, 80, allowedIpsList.get(0));
        }

        @Test
        @DisplayName("허가된 IP(123.31.100.20/32)만 80 인바운드를 허용한다")
        void shouldAllowInboundFromSecondAllowedCidr() {
            assertIngressCidrRule(adminAlbSgLogicalId, 80, allowedIpsList.get(1));
        }

        @Test
        @DisplayName("0.0.0.0/0 인바운드가 없다 — 외부 차단 확인 ★")
        void shouldNotAllowPublicInbound() {
            // Admin ALB SG에 anyIpv4 인바운드가 존재하면 안 됨
            assertThat(ingressRulesFor(adminAlbSgLogicalId))
                    .noneSatisfy(props -> assertThat(props.get("CidrIp")).isEqualTo("0.0.0.0/0"));
        }

        @Test
        @DisplayName("Admin API 포트로 아웃바운드를 허용한다")
        void shouldAllowOutboundToAdminApi() {
            assertEgressToSecurityGroup(adminAlbSgLogicalId, adminApiSgLogicalId, ADMIN_SERVER_PORT);
        }

        @Test
        @DisplayName("Admin Web 포트로 아웃바운드를 허용한다")
        void shouldAllowOutboundToAdminWeb() {
            assertEgressToSecurityGroup(adminAlbSgLogicalId, adminWebSgLogicalId, ADMIN_WEB_PORT);
        }
    }


    @Nested
    @DisplayName("Admin Web Security Group")
    class AdminWebSgTest {

        @Test
        @DisplayName("Admin ALB에서만 adminWebPort 인바운드를 허용한다")
        void shouldAllowInboundFromAdminAlbOnly() {
            assertIngressFromSecurityGroup(adminWebSgLogicalId, adminAlbSgLogicalId, ADMIN_WEB_PORT);
        }

        @Test
        @DisplayName("Admin API 포트로 아웃바운드를 허용한다")
        void shouldAllowOutboundToAdminApiPortOnly() {
            assertEgressToSecurityGroup(adminWebSgLogicalId, adminApiSgLogicalId, ADMIN_SERVER_PORT);
        }
    }


    @Nested
    @DisplayName("Admin API Security Group")
    class AdminApiSgTest {

        @Test
        @DisplayName("Admin Web에서만 adminServerPort 인바운드를 허용한다 ★")
        void shouldAllowInboundFromAdminAlbOnly() {
            assertIngressFromSecurityGroup(adminApiSgLogicalId, adminWebSgLogicalId, ADMIN_SERVER_PORT);
        }
    }

    @DisplayName("Security Group은 5개 생성된다 - CustomerAlbSg, CusteomerApiSg, AdminAlbSg, AdminWebSg, AdminApiSg")
    @Test
    void should_create_five_security_groups(){
        template.resourceCountIs("AWS::EC2::SecurityGroup",5);
    }
}
