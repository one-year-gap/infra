import boto3


def _safe_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def _extract_env_and_secret_metadata(task_definition):
    container_defs = task_definition.get("containerDefinitions", [])
    if not container_defs:
        return set(), set(), set()

    # 운영 표준상 첫 번째 컨테이너를 애플리케이션 컨테이너로 간주한다.
    # (멀티 컨테이너 구조라면 필요 시 컨테이너 이름 기반 선택 로직으로 확장 가능)
    primary = container_defs[0]

    env_names = {entry.get("name", "") for entry in primary.get("environment", [])}
    secrets = primary.get("secrets", [])
    secret_names = {entry.get("name", "") for entry in secrets}
    secret_value_froms = {entry.get("valueFrom", "") for entry in secrets if entry.get("valueFrom")}
    return env_names, secret_names, secret_value_froms


def _check_rds_sg_isolation(ec2_client, rds_sg_id, fastapi_sg_id):
    if not rds_sg_id or not fastapi_sg_id:
        return

    response = ec2_client.describe_security_groups(GroupIds=[rds_sg_id])
    groups = response.get("SecurityGroups", [])
    if not groups:
        raise Exception(f"RDS SG not found: {rds_sg_id}")

    ingress_rules = groups[0].get("IpPermissions", [])
    for rule in ingress_rules:
        # PostgreSQL(5432)만이 아니라 all-traffic ingress까지 포함해서 차단.
        from_port = rule.get("FromPort")
        to_port = rule.get("ToPort")
        is_open_to_pg = from_port is None or to_port is None or (from_port <= 5432 <= to_port)
        if not is_open_to_pg:
            continue

        for pair in rule.get("UserIdGroupPairs", []):
            if pair.get("GroupId") == fastapi_sg_id:
                raise Exception(
                    "RDS isolation violation: FastAPI SG is allowed in RDS SG ingress"
                )


def _check_secret_access_for_role(iam_client, role_arn, role_label, rds_secret_arn):
    if not role_arn or not rds_secret_arn:
        return

    # 실제 AssumeRole 없이 정책 시뮬레이션으로 접근 가능성을 판별한다.
    result = iam_client.simulate_principal_policy(
        PolicySourceArn=role_arn,
        ActionNames=["secretsmanager:GetSecretValue"],
        ResourceArns=[rds_secret_arn],
    )

    decisions = result.get("EvaluationResults", [])
    for decision in decisions:
        if decision.get("EvalDecision", "").upper() == "ALLOWED":
            raise Exception(
                f"RDS isolation violation: FastAPI {role_label} can read RDS secret"
            )


def handler(event, _context):
    """
    Runtime Guard (P1 RDS 격리/구성 가드).

    검증 항목:
    1) FastAPI task definition에 DB 환경변수/시크릿 키가 없는지
    2) FastAPI SG가 RDS SG 인바운드에 허용되어 있지 않은지
    3) FastAPI task/execution role이 RDS secret 조회 권한을 갖지 않는지
    4) (옵션) FastAPI/Worker EFS Access Point 분리가 지켜지는지

    실패 시 예외를 던져 Step Functions에서 즉시 실패 분기로 전환한다.
    """
    ecs = boto3.client("ecs")
    ec2 = boto3.client("ec2")
    iam = boto3.client("iam")

    fastapi_td_arn = event.get("fastApiTaskDefinitionArn")
    worker_td_arn = event.get("workerTaskDefinitionArn")

    if not fastapi_td_arn:
        raise Exception("fastApiTaskDefinitionArn is required")

    disallowed_names = set(_safe_list(event.get("disallowedEnvNames")))
    rds_sg_id = str(event.get("rdsSecurityGroupId", "")).strip()
    fastapi_sg_id = str(event.get("fastApiSecurityGroupId", "")).strip()
    rds_secret_arn = str(event.get("rdsSecretArn", "")).strip()

    fastapi_td = ecs.describe_task_definition(taskDefinition=fastapi_td_arn)["taskDefinition"]
    env_names, secret_names, secret_value_froms = _extract_env_and_secret_metadata(fastapi_td)

    leaked = sorted({name for name in (env_names | secret_names) if name in disallowed_names})
    if leaked:
        raise Exception(f"FastAPI task definition contains disallowed DB keys: {leaked}")

    if rds_secret_arn:
        # 자동 주입(ecs secrets) 경로 자체를 금지한다.
        # ARN full match와 ARN prefix(:json-key) 패턴 모두 차단.
        for value_from in secret_value_froms:
            if value_from == rds_secret_arn or value_from.startswith(rds_secret_arn + ":"):
                raise Exception(
                    "RDS isolation violation: FastAPI container references RDS secret in ECS secrets"
                )

    _check_rds_sg_isolation(ec2, rds_sg_id, fastapi_sg_id)
    # SecretManager 자동 주입은 execution role 권한 경로를 탈 수 있기 때문에
    # task role + execution role을 모두 검사해야 완전한 차단이 된다.
    _check_secret_access_for_role(iam, fastapi_td.get("taskRoleArn", ""), "task role", rds_secret_arn)
    _check_secret_access_for_role(iam, fastapi_td.get("executionRoleArn", ""), "execution role", rds_secret_arn)

    # Access Point 분리 강제(옵션): 둘 다 값이 있을 때만 비교.
    if bool(event.get("enforceSeparateEfsAccessPoints", False)):
        fastapi_ap = str(event.get("fastApiEfsAccessPointId", "")).strip()
        worker_ap = str(event.get("workerEfsAccessPointId", "")).strip()
        if fastapi_ap and worker_ap and fastapi_ap == worker_ap:
            raise Exception("EFS access point isolation violation: FastAPI and Worker share same access point")

    # Worker task definition ARN이 넘어오면 존재성 점검까지 수행한다.
    if worker_td_arn:
        ecs.describe_task_definition(taskDefinition=worker_td_arn)

    return {
        "status": "PASS",
        "checkedFastApiTaskDefinition": fastapi_td_arn,
        "checkedWorkerTaskDefinition": worker_td_arn,
    }
