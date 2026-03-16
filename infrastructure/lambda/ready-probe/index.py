import json
import urllib.request
import urllib.error


def _lookup_path(payload, dotted_path):
    # dotted_path 예: checks.modelLoaded
    node = payload
    for token in dotted_path.split("."):
        if not isinstance(node, dict) or token not in node:
            return None
        node = node[token]
    return node


def handler(event, _context):
    url = event.get("url")
    timeout_sec = int(event.get("timeoutSec", 5))
    expected_release_tag = event.get("expectedReleaseTag", "")
    # requiredChecks는 운영 계약이다.
    # 예: ["ready","checks.modelLoaded","checks.efsWritable","checks.dependenciesReady"]
    required_checks = event.get("requiredChecks", []) or []

    if not url:
        raise Exception("url is required")

    # GET 요청 객체 구성
    request = urllib.request.Request(url, method="GET")

    try:
        # 네트워크 예외/타임아웃
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            status_code = int(response.status)
            body = response.read().decode("utf-8")


            if status_code != 200:
                raise Exception(f"/ready returned {status_code}: {body}")

            if expected_release_tag:
                try:
                    payload = json.loads(body) if body else {}
                except json.JSONDecodeError:
                    # JSON 파싱 실패
                    payload = {}

                actual_tag = str(payload.get("releaseTag", "")).strip()
                if actual_tag and actual_tag != expected_release_tag:
                    raise Exception(
                        f"releaseTag mismatch. expected={expected_release_tag}, actual={actual_tag}"
                    )
            else:
                try:
                    payload = json.loads(body) if body else {}
                except json.JSONDecodeError:
                    payload = {}

            for check_path in required_checks:
                value = _lookup_path(payload, str(check_path))
                if value is not True:
                    raise Exception(
                        f"/ready contract failed: '{check_path}' must be true, actual={value}"
                    )

            return {
                "statusCode": status_code,
                "body": body,
                "checkedReleaseTag": expected_release_tag,
                "checkedPaths": required_checks,
            }
    except urllib.error.HTTPError as error:
        # 서버가 오류 상태코드를 내려준 경우 (4xx/5xx)
        error_body = error.read().decode("utf-8") if error.fp else ""
        raise Exception(f"/ready HTTPError {error.code}: {error_body}")
    except Exception as error:
        # 타임아웃, 연결 실패, 파싱 오류
        raise Exception(f"/ready probe failed: {str(error)}")
