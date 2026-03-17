from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta, timezone
from functools import lru_cache

try:
    import boto3
except ImportError:  # pragma: no cover - local unit test fallback
    boto3 = None

VENDOR_PATH = os.path.join(os.path.dirname(__file__), "vendor")
if VENDOR_PATH not in sys.path:
    sys.path.insert(0, VENDOR_PATH)

import pg8000.dbapi


DEFAULT_VALIDATION_MODE = "LEGACY_PAYLOAD"
OUTBOX_REQUEST_COUNTS_MODE = "OUTBOX_REQUEST_COUNTS"


def _container_exit_code(batch_task_result):
    task = _first_task(batch_task_result)
    if not task:
        return None

    containers = task.get("containers") or task.get("Containers") or []
    if not containers:
        return None

    code = containers[0].get("exitCode")
    if code is None:
        code = containers[0].get("ExitCode")
    return code


def _first_task(batch_task_result):
    tasks = batch_task_result.get("tasks") or batch_task_result.get("Tasks") or []
    if not tasks:
        return None
    return tasks[0]


def _task_timestamp(task, lower_key, upper_key):
    raw = task.get(lower_key)
    if raw is None:
        raw = task.get(upper_key)
    if raw is None:
        return None
    if isinstance(raw, datetime):
        if raw.tzinfo is None:
            return raw.replace(tzinfo=timezone.utc)
        return raw.astimezone(timezone.utc)
    return datetime.fromisoformat(str(raw).replace("Z", "+00:00")).astimezone(timezone.utc)


def _validation_mode(validation):
    raw = validation.get("mode") or os.environ.get("VALIDATION_MODE") or DEFAULT_VALIDATION_MODE
    return str(raw).strip().upper()


def _fail(reason, **extra):
    payload = {"status": "FAIL", "reason": reason}
    payload.update(extra)
    return payload


def _pending(reason, **extra):
    payload = {"status": "PENDING", "reason": reason}
    payload.update(extra)
    return payload


def _pass(reason, **extra):
    payload = {"status": "PASS", "reason": reason}
    payload.update(extra)
    return payload


def _validate_legacy_payload(validation, execution_input):
    validation_data = execution_input.get("validationData", {}) or {}

    min_processed = int(validation.get("minProcessedCount", 0) or 0)
    processed_count = int(validation_data.get("processedCount", 0) or 0)
    if processed_count < min_processed:
        return _fail(
            f"processedCount={processed_count} < minProcessedCount={min_processed}",
            processedCount=processed_count,
            minProcessedCount=min_processed,
        )

    required_files = validation.get("requiredResultFiles", []) or []
    actual_files = validation_data.get("resultFiles", []) or []
    missing = sorted(list(set(required_files) - set(actual_files)))
    if missing:
        return _fail(
            "missing required result files",
            missingResultFiles=missing,
            resultFiles=actual_files,
        )

    return _pass(
        "legacy payload validation passed",
        processedCount=processed_count,
        resultFiles=actual_files,
    )


@lru_cache(maxsize=1)
def _secrets_manager_client():
    if boto3 is None:
        raise RuntimeError("boto3 is required for OUTBOX_REQUEST_COUNTS validation mode")
    return boto3.client("secretsmanager")


@lru_cache(maxsize=1)
def _db_secret():
    secret_id = os.environ.get("DB_SECRET_ID")
    if not secret_id:
        raise RuntimeError("business validator DB_SECRET_ID is missing")

    secret_value = _secrets_manager_client().get_secret_value(SecretId=secret_id)
    secret_string = secret_value.get("SecretString")
    if not secret_string:
        raise RuntimeError("business validator DB secret string is empty")
    return json.loads(secret_string)


def _db_name():
    secret = _db_secret()
    return os.environ.get("DB_NAME") or secret.get("dbname") or "holliverse"


def _job_lookup_grace_seconds():
    raw = os.environ.get("JOB_LOOKUP_GRACE_SECONDS", "300")
    seconds = int(raw)
    if seconds <= 0:
        raise RuntimeError("JOB_LOOKUP_GRACE_SECONDS must be positive")
    return seconds


def _connect():
    secret = _db_secret()
    return pg8000.dbapi.connect(
        host=secret["host"],
        port=int(secret.get("port", 5432)),
        database=_db_name(),
        user=secret["username"],
        password=secret["password"],
        timeout=5,
    )


def _find_job_instance_id(cursor, worker_started_at, worker_stopped_at):
    grace = timedelta(seconds=_job_lookup_grace_seconds())
    lookup_start = worker_started_at - grace
    lookup_end = (worker_stopped_at or datetime.now(timezone.utc)) + grace

    cursor.execute(
        """
        select bje.job_instance_id, bje.start_time, bje.end_time
          from batch_job_execution bje
         where bje.start_time between %s and %s
         order by bje.start_time desc
         limit 1
        """,
        (lookup_start, lookup_end),
    )
    row = cursor.fetchone()
    if not row:
        return None
    return {
        "jobInstanceId": int(row[0]),
        "jobStartedAt": row[1].replace(tzinfo=timezone.utc).isoformat() if row[1] else None,
        "jobEndedAt": row[2].replace(tzinfo=timezone.utc).isoformat() if row[2] else None,
    }


def _load_outbox_counts(cursor, job_instance_id):
    cursor.execute(
        """
        select
            count(*) filter (where type::text = 'REQUEST') as request_count,
            count(*) filter (where type::text = 'REQUEST' and analysis_status::text = 'COMPLETED') as success_count,
            count(*) filter (
                where type::text = 'REQUEST'
                  and (
                    analysis_status::text = 'FAILED'
                    or dispatch_status::text = 'DEAD'
                  )
            ) as terminal_failure_count,
            count(*) filter (where type::text = 'REQUEST' and dispatch_status::text = 'RETRY') as retry_count,
            count(*) filter (where type::text = 'REQUEST' and dispatch_status::text in ('READY', 'SENT', 'ACKED')) as dispatch_pending_count,
            count(*) filter (where type::text = 'REQUEST' and analysis_status::text = 'IN_PROGRESS') as analysis_in_progress_count
          from analysis_dispatch_outbox
         where job_instance_id = %s
        """,
        (job_instance_id,),
    )
    row = cursor.fetchone()
    request_count = int(row[0] or 0)
    success_count = int(row[1] or 0)
    terminal_failure_count = int(row[2] or 0)
    retry_count = int(row[3] or 0)
    dispatch_pending_count = int(row[4] or 0)
    analysis_in_progress_count = int(row[5] or 0)
    pending_count = max(request_count - success_count - terminal_failure_count, 0)

    return {
        "requestCount": request_count,
        "successCount": success_count,
        "terminalFailureCount": terminal_failure_count,
        "pendingCount": pending_count,
        "retryCount": retry_count,
        "dispatchPendingCount": dispatch_pending_count,
        "analysisInProgressCount": analysis_in_progress_count,
    }


def _evaluate_outbox_counts(job_instance_id, counts):
    payload = {"jobInstanceId": job_instance_id, **counts}

    if counts["requestCount"] == 0:
        return _pass("no request rows created for this batch run", **payload)

    if counts["pendingCount"] > 0:
        return _pending("analysis dispatch outbox is not terminal yet", **payload)

    if counts["terminalFailureCount"] > 0:
        return _fail("terminal failures detected in analysis dispatch outbox", **payload)

    return _pass("all request rows reached completed state", **payload)


def _validate_outbox_request_counts(batch_task_result):
    task = _first_task(batch_task_result)
    if not task:
        raise RuntimeError("business validation failed: worker task metadata is missing")

    worker_started_at = _task_timestamp(task, "startedAt", "StartedAt")
    worker_stopped_at = _task_timestamp(task, "stoppedAt", "StoppedAt")
    if worker_started_at is None:
        raise RuntimeError("business validation failed: worker startedAt is missing")

    conn = _connect()
    cursor = conn.cursor()
    try:
        job_info = _find_job_instance_id(cursor, worker_started_at, worker_stopped_at)
        if not job_info:
            return _pending(
                "batch_job_execution row not found for worker time window yet",
                workerStartedAt=worker_started_at.isoformat(),
                workerStoppedAt=worker_stopped_at.isoformat() if worker_stopped_at else None,
            )

        counts = _load_outbox_counts(cursor, job_info["jobInstanceId"])
        result = _evaluate_outbox_counts(job_info["jobInstanceId"], counts)
        result.update(
            {
                "workerStartedAt": worker_started_at.isoformat(),
                "workerStoppedAt": worker_stopped_at.isoformat() if worker_stopped_at else None,
                "jobStartedAt": job_info["jobStartedAt"],
                "jobEndedAt": job_info["jobEndedAt"],
            }
        )
        return result
    finally:
        cursor.close()
        conn.close()


def handler(event, _context):
    """
    배치 비즈니스 검증기.

    지원 모드:
    - LEGACY_PAYLOAD: executionInput.validationData의 processedCount/resultFiles 검증
    - OUTBOX_REQUEST_COUNTS: analysis_dispatch_outbox에서 request/success/failure 집계
    """
    validation = event.get("validation", {}) or {}
    if not bool(validation.get("enabled", False)):
        return {"status": "SKIP", "reason": "business validation disabled"}

    batch_task = event.get("batchTaskResult", {}) or {}
    exit_code = _container_exit_code(batch_task)
    if exit_code is None:
        raise RuntimeError("business validation failed: worker exit code is missing")
    if int(exit_code) != 0:
        raise RuntimeError(f"business validation failed: worker exit code is non-zero ({exit_code})")

    mode = _validation_mode(validation)
    if mode == OUTBOX_REQUEST_COUNTS_MODE:
        result = _validate_outbox_request_counts(batch_task)
        result["mode"] = mode
        return result

    execution_input = event.get("executionInput", {}) or {}
    result = _validate_legacy_payload(validation, execution_input)
    result["mode"] = mode
    return result
