def _container_exit_code(batch_task_result):
    tasks = batch_task_result.get("tasks") or batch_task_result.get("Tasks") or []
    if not tasks:
        return None

    containers = tasks[0].get("containers") or tasks[0].get("Containers") or []
    if not containers:
        return None

    code = containers[0].get("exitCode")
    if code is None:
        code = containers[0].get("ExitCode")
    return code


def handler(event, _context):
    """
    배치 비즈니스 검증기.

    설계 의도:
    - exitCode=0만으로 성공 처리하지 않고, 도메인 결과 조건(처리 건수/결과 파일)을 추가 검증한다.
    - 검증 기준은 실행 입력(executionInput.validationData)으로 주입해 코드 하드코딩을 피한다.

    입력 예시:
    {
      "batchTaskResult": {... describeTasks 응답 ...},
      "executionInput": {
        "validationData": {
          "processedCount": 123,
          "resultFiles": ["summary.json", "detail.csv"]
        }
      },
      "validation": {
        "enabled": true,
        "minProcessedCount": 10,
        "requiredResultFiles": ["summary.json"]
      }
    }
    """
    # validation 블록은 Step Functions에서 외부설정 값을 그대로 전달받는다.
    # (코드 수정 없이 운영 기준만 교체 가능)
    validation = event.get("validation", {}) or {}
    enabled = bool(validation.get("enabled", False))
    if not enabled:
        return {"status": "SKIP", "reason": "business validation disabled"}

    # executionInput은 StartExecution 시점 입력 전체.
    # 도메인 레벨 검증 데이터(processedCount/resultFiles)를 이 경로로 전달받는다.
    batch_task = event.get("batchTaskResult", {}) or {}
    execution_input = event.get("executionInput", {}) or {}
    validation_data = execution_input.get("validationData", {}) or {}

    # 안전장치: 호출 순서가 바뀌어 exitCode가 비정상인데 들어오면 즉시 실패.
    exit_code = _container_exit_code(batch_task)
    if exit_code is None:
        raise Exception("business validation failed: worker exit code is missing")
    if int(exit_code) != 0:
        raise Exception(f"business validation failed: worker exit code is non-zero ({exit_code})")

    min_processed = int(validation.get("minProcessedCount", 0) or 0)
    processed_count = int(validation_data.get("processedCount", 0) or 0)
    if processed_count < min_processed:
        raise Exception(
            f"business validation failed: processedCount={processed_count} < minProcessedCount={min_processed}"
        )

    required_files = validation.get("requiredResultFiles", []) or []
    actual_files = validation_data.get("resultFiles", []) or []
    missing = sorted(list(set(required_files) - set(actual_files)))
    if missing:
        raise Exception(f"business validation failed: missing result files {missing}")

    return {
        "status": "PASS",
        "processedCount": processed_count,
        "resultFiles": actual_files,
    }
