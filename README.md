# 🌌 Holliverse - Infra Repository

## 🏆 URECA 3기 최종 융합프로젝트 우수상

> **고객 데이터 기반 초개인화 통신 서비스 분석 및 추천 플랫폼**  
> Holliverse는 분산된 고객 데이터를 통합해, 사용자에게는 최적의 상품을 추천하고  
> 운영자에게는 정교한 비즈니스 인사이트를 제공하는 **CRM / CDP(Customer Data Platform)** 지향 서비스입니다.

---

## 📦 What This Repository Manages

이 저장소는 Holliverse의 AWS 인프라를 코드로 관리하기 위한 **중앙 Infra Repository** 입니다.

주요 관리 대상은 다음과 같습니다.

- VPC, Subnet, Route Table, NAT Gateway
- Application Load Balancer (Customer / Admin)
- ECS Cluster / ECS Service / Task Definition
- RDS PostgreSQL
- MSK / MSK Connect
- Monitoring EC2 (Grafana, Prometheus, pg_exporter)
- Route53 / ACM / CloudFront / DNS
- GitHub Actions 기반 CI/CD/CT 워크플로
- 유휴 시간대 리소스 중지/재기동 자동화

즉, 서비스 레포지토리가 비즈니스 로직을 관리한다면,  
이 저장소는 **배포 경로, 네트워크 경계, 운영 정책, 공통 인프라 리소스**를 관리합니다.

---

## 🏗️ Infrastructure Overview

> **2 ALB · 2 AZ · 4 Subnets · 9 Security Groups**  
> **서비스 배포 50.9% 단축 · 모니터링 반영 81.6% 단축**  
> 고객/관리자 트래픽을 네트워크 레벨에서 먼저 분리하고,  
> 중앙 Infra Repository를 통해 배포 정책과 운영 흐름을 일관되게 관리합니다.

### ✨ 핵심 성과

| 항목 | 수치 | 의미 |
|---|---:|---|
| 네트워크 진입점 | **2 ALB** | Customer / Admin 트래픽을 진입점부터 분리 |
| 가용 영역 | **2 AZ** | AZ 단위 분산 구조 확보 |
| 서브넷 구조 | **4 Subnets** | Public 2 / Private 2 분리 |
| 보안 경계 | **9 Security Groups** | 리소스 역할별 최소 권한 통신 제어 |
| 서비스 배포 시간 | **449.1초 → 220.4초** | ECS service rollout 전환으로 **50.9% 단축** |
| 모니터링 반영 시간 | **449.1초 → 82.7초** | MonitoringStack 분리로 **81.6% 단축** |
| 서비스 배포 처리량 | **8.0 → 16.3회/시간** | 동일 시간 내 배포 가능 횟수 **+103.7%** |
| 모니터링 배포 처리량 | **8.0 → 43.5회/시간** | monitoring-only 변경 반영 효율 **+442.8%** |
| 템플릿 동기화 범위 | **6개 서비스 레포** | 중앙 정책 변경을 멀티 레포에 자동 반영 |
| 로그 적재 지연 | **최대 60초** | raw click log를 운영 가능한 주기로 S3 적재 |
| 로그 적재 개선율 | **98.3%** | 1시간 파티션 기준 대비 더 빠른 원본 저장 |

---

## 🎯 Infrastructure Design Principles

이 인프라는 아래 5가지 원칙을 기준으로 설계했습니다.

1. **고객 트래픽과 관리자 트래픽은 네트워크 레벨에서 먼저 분리한다.**
2. **애플리케이션과 데이터베이스는 기본적으로 Private Subnet에 배치한다.**
3. **같은 VPC 내부라도 Security Group으로 통신 방향과 포트를 명시적으로 제한한다.**
4. **운영성보다 보안을 우선해야 하는 자원(Admin, DB, Monitoring)은 직접 노출하지 않는다.**
5. **배포는 서비스별로 독립적으로 하되, 정책과 실행 흐름은 중앙 Infra Repository에서 통제한다.**

---

## 🔄 Request Flow

### 👤 Customer Flow
`user -> root domain (Vercel) -> api.<domain> -> Customer ALB -> Customer API ECS -> RDS`

### 🛠️ Admin Flow
`admin user -> admin.<domain> -> Admin ALB -> Admin Web ECS -> Admin API ECS -> RDS`

### 🧠 Internal / Analysis Flow
`Customer/Admin API -> MSK -> MSK Connect / Intelligence Server / Batch Worker`

### 📈 Monitoring Flow
`Application / DB / AWS Metrics -> Prometheus / pg_exporter / CloudWatch -> Grafana`

---

## 🚪 Why Two ALBs?

Holliverse는 고객용 웹 트래픽과 관리자용 운영 트래픽의 성격이 다릅니다.

- 고객 트래픽은 외부 사용자가 접근하는 공개 서비스입니다.
- 관리자 트래픽은 허용된 IP 대역에서만 접근해야 하는 운영 시스템입니다.

따라서 하나의 ALB에서 애플리케이션 레벨 권한 제어만으로 구분하기보다,  
**Customer ALB / Admin ALB를 분리해 네트워크 진입점 자체를 나누는 방식**을 선택했습니다.

이 구조를 통해 다음과 같은 효과를 얻었습니다.

- 고객/관리자 라우팅 규칙 분리
- 관리자 인바운드 IP 제한 적용
- 보안 정책과 장애 영향 범위 분리
- 추후 WAF / 인증 정책 / 운영 정책의 독립 적용 가능

즉, 애플리케이션 내부 권한 처리에만 의존하지 않고  
**네트워크 경계에서부터 먼저 분리하는 보안 구조**를 갖추게 되었습니다.

---

## 🔐 Security Group Policy

| Source | Destination | Port | Purpose |
|---|---|---:|---|
| Internet | Customer ALB | 443 | 고객 서비스 HTTPS 진입점 |
| Allowed Admin CIDRs | Admin ALB | 443 | 관리자 콘솔 접근 |
| Customer ALB | Customer API ECS | 8080 | 고객 API 전달 |
| Admin ALB | Admin Web ECS | 3000 | 관리자 웹 전달 |
| Admin Web ECS | Admin API ECS | 8080 | 관리자 웹-API 내부 통신 |
| Customer API ECS | RDS | 5432 | 고객 서비스 DB 접근 |
| Admin API ECS | RDS | 5432 | 관리자 서비스 DB 접근 |
| Intelligence Server | RDS | 5432 | 분석 데이터 저장/조회 |
| Monitoring EC2 | RDS | 5432 | DB 관측 |
| ECS Services | MSK | 9098 | Kafka IAM/TLS 통신 |

역할별 Security Group을 분리해 적용함으로써,  
“같은 VPC 안이면 모두 통신 가능”한 구조를 피하고  
**누가 누구에게 어떤 포트로 접근할 수 있는지 명시적으로 제어**합니다.

---

## 🚀 Deployment Strategy

이 저장소는 서비스별 애플리케이션 레포지토리와 분리된 **중앙 Infra Repository** 로 동작합니다.

배포 전략은 다음과 같습니다.

- 서비스 레포지토리는 애플리케이션 코드와 Docker 이미지 빌드에 집중합니다.
- 인프라 레포지토리는 AWS 리소스 정의와 배포 정책을 관리합니다.
- 서비스 이미지 변경 시에는 **ECS service rollout 중심**으로 배포합니다.
- 인프라 구조 변경 시에만 **CloudFormation / CDK stack deploy** 를 수행합니다.
- Monitoring과 같이 변경 성격이 다른 리소스는 **별도 stack** 으로 분리해 반영합니다.

### ⚡ 배포 구조 개선

기존에는 `cdk deploy EcsClusterStack` 중심 구조로 인해,  
서비스 하나의 이미지가 변경되어도 ECS 관련 스택 전체를 다시 반영하는 흐름에 가까웠습니다.

현재는 아래 순서로 동작합니다.

1. 현재 Task Definition 조회  
2. 새 이미지 태그를 반영한 Revision 등록  
3. `aws ecs update-service` 실행  
4. `aws ecs wait services-stable` 로 안정화 대기  

즉, 배포를 **stack deploy 중심**에서 **service rollout 중심**으로 전환했습니다.

그 결과:

- **서비스 배포 시간:** `449.1초 → 220.4초` (**50.9% 단축**)
- **서비스 배포 처리량:** `8.0 → 16.3회/시간` (**+103.7%**)
- **모니터링 반영 시간:** `449.1초 → 82.7초` (**81.6% 단축**)
- **모니터링 배포 처리량:** `8.0 → 43.5회/시간` (**+442.8%**)

---

## 📊 Observability

현재 모니터링 시스템은 다음과 같은 지표를 중점적으로 수집합니다.

- ECS CPU / Memory / Task Count
- ALB Request Count / Target Response Time / HTTP 4xx, 5xx
- RDS CPU / Connections / Freeable Memory / Read/Write Latency
- Spring Boot Actuator / Micrometer 기반 애플리케이션 메트릭
- PostgreSQL 내부 상태(pg_exporter)
- 배포 이후 ECS service steady state 도달 시간

운영자는 Grafana를 통해 애플리케이션, DB, AWS 인프라 지표를 한 곳에서 확인할 수 있으며,  
접근은 **SSM Port Forwarding** 으로만 허용합니다.

즉, 관측 도구 역시 외부에 직접 노출하지 않고  
**내부망 기반 + IAM/Secrets 중심의 운영 보안 구조**를 유지합니다.

---

## 🧪 CI/CD/CT Quality Gate

빠른 배포를 안정적으로 유지하기 위해 reusable workflow 기반 CT를 구성하고,  
PostgreSQL 테스트 환경 위에서 Gradle 테스트와 JaCoCo 리포트를 평가합니다.

현재 품질 게이트는 다음 임계치로 관리합니다.

- **overall line coverage: 70%**
- **overall branch coverage: 70%**
- **changed line coverage: 80%**
- **changed branch coverage: 80%**

즉,  
**CI는 실수를 빠르게 드러내고,**  
**CD는 변경을 작게 반영하며,**  
**CT는 그 빠른 흐름을 버틸 수 있게 만드는 최소 품질 기준**으로 동작합니다.

---

## 🪵 Log Pipeline

MSK Connect S3 Sink는 아래 기준으로 운영합니다.

- `flush.size=1000`
- `rotate.interval.ms=60000`
- `partition.duration.ms=3600000`

이를 통해 raw click log를 **최대 60초 이내**에 S3로 적재할 수 있고,  
1시간 파티션 기준과 비교하면 **98.3% 더 빠르게** 원본 로그를 저장할 수 있습니다.

---

## 📚 Related Documents

- [AWS CDK(Java)를 선택한 이유](https://one-year-gap.github.io/docs/2026/02/10/aws-cdk-with-java%EB%A5%BC-%EC%84%A0%ED%83%9D%ED%95%9C-%EC%9D%B4%EC%9C%A0/)
- [Network Architecture](https://one-year-gap.github.io/docs/2026/03/17/infra-architecture-network/)
- [CI/CD/CT Architecture](https://one-year-gap.github.io/docs/2026/03/17/infra-architecture-ci-cd-ct/)
