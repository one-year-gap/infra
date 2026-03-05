import os
from datetime import datetime, timedelta, timezone

import boto3


def _utc_now():
    return datetime.now(timezone.utc)


def handler(_event, _context):
    """
    On-Demand Watchdog.

    목적:
    - Step Functions 수동 중단/권한 오류 등으로 cleanup 단계가 건너뛰어졌을 때,
      FastAPI 서비스가 켜진 채 남는 사고를 자동 복구한다.

    동작:
    1) 상태머신 RUNNING execution 존재 여부 확인
    2) execution이 없고 FastAPI desiredCount>0 상태가 일정 시간 지속되면 desired=0으로 강제 복구
    """
    cluster_arn = os.environ["CLUSTER_ARN"]
    service_name = os.environ["FASTAPI_SERVICE_NAME"]
    state_machine_arn = os.environ["STATE_MACHINE_ARN"]
    idle_minutes = int(os.environ.get("IDLE_MINUTES", "20"))

    ecs = boto3.client("ecs")
    sfn = boto3.client("stepfunctions")

    # 상태머신이 실제로 동작 중이면 scale-down을 건드리지 않는다.
    running = sfn.list_executions(
        stateMachineArn=state_machine_arn,
        statusFilter="RUNNING",
        maxResults=1,
    ).get("executions", [])

    # FastAPI 서비스 현재 desired/running 상태 확인.
    service_resp = ecs.describe_services(cluster=cluster_arn, services=[service_name])
    services = service_resp.get("services", [])
    if not services:
        raise Exception(f"FastAPI service not found: {service_name}")

    service = services[0]
    desired = int(service.get("desiredCount", 0))
    running_count = int(service.get("runningCount", 0))

    if desired == 0 and running_count == 0:
        return {
            "status": "NO_ACTION",
            "reason": "already scaled down",
            "desired": desired,
            "running": running_count,
        }

    if running:
        return {
            "status": "NO_ACTION",
            "reason": "workflow is still running",
            "desired": desired,
            "running": running_count,
            "runningExecutionArn": running[0].get("executionArn", ""),
        }

    # 서비스가 최근에 갱신된 경우를 배려해 짧은 유예시간 후 정리한다.
    # deployments[].updatedAt이 없으면 즉시 정리한다.
    deployments = service.get("deployments", [])
    latest_updated_at = None
    for dep in deployments:
        updated_at = dep.get("updatedAt")
        if updated_at and (latest_updated_at is None or updated_at > latest_updated_at):
            latest_updated_at = updated_at

    if latest_updated_at is not None:
        threshold = _utc_now() - timedelta(minutes=idle_minutes)
        if latest_updated_at > threshold:
            return {
                "status": "NO_ACTION",
                "reason": "within idle grace period",
                "desired": desired,
                "running": running_count,
                "idleMinutes": idle_minutes,
            }

    # 유휴 상태로 판단되면 desired=0으로 강제 복구.
    ecs.update_service(cluster=cluster_arn, service=service_name, desiredCount=0)
    return {
        "status": "RECOVERED",
        "action": "scaled_down_to_zero",
        "previousDesired": desired,
        "previousRunning": running_count,
    }
