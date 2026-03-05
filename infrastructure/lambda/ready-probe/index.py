import json
import urllib.request
import urllib.error
from urllib.parse import urlparse

def _lookup_path(payload, dotted_path):
    # dotted_path 예: checks.modelLoaded
    node = payload
    for token in dotted_path.split("."):
        if not isinstance(node, dict) or token not in node:
            return None
        node = node[token]
    return node


def handler(event, _context):
    """
    Step 5/6 ready gate.
    - /ready가 200이면 통과
    - 나머지는 예외로 처리해 Step Functions Retry로 넘긴다.

    입력:
    - url: ready endpoint
    - timeoutSec: HTTP timeout seconds
    - expectedReleaseTag: optional, /ready 응답의 releaseTag와 비교
    - requiredChecks: optional, true여야 하는 필드 경로 목록

    출력:
    - statusCode/body/checkedReleaseTag

    예외 처리 규칙:
    - 어떤 종류의 예외든 raise 하여 Step Functions Retry/Catch로 위임한다.
    - 즉, 이 Lambda는 "성공 여부 판정기" 역할만 수행하고 재시도 정책은 오케스트레이터가 담당한다.
    """
    url = event.get("url")
    timeout_sec = int(event.get("timeoutSec", 5))
    expected_release_tag = event.get("expectedReleaseTag", "")
    # requiredChecks는 운영 계약이다.
    # 예: ["ready","checks.modelLoaded","checks.efsWritable","checks.dependenciesReady"]
    required_checks = event.get("requiredChecks", []) or []

    if not url:
        raise Exception("url is required")
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
         raise ValueError(f"unsupported url scheme: {parsed.scheme}")
    # GET 요청 객체 구성
    request = urllib.request.Request(url, method="GET")

    try:
        # 네트워크 예외/타임아웃은 아래 except로 내려가며 retry 대상이 된다.
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            status_code = int(response.status)
            body = response.read().decode("utf-8")

            # 200만 성공으로 간주
            if status_code != 200:
                raise Exception(f"/ready returned {status_code}: {body}")

            # ready 응답에 releaseTag를 포함시키는 운영규칙을 쓸 경우 정합성 검증
            if expected_release_tag:
                try:
                    payload = json.loads(body) if body else {}
                except json.JSONDecodeError:
                    # JSON 파싱 실패는 releaseTag 비교를 생략한다.
                    # (/ready 본문 포맷이 일시적으로 바뀌어도 200이면 통과시키는 보수적 정책)
                    payload = {}

                actual_tag = str(payload.get("releaseTag", "")).strip()
                # releaseTag가 응답에 존재하고 expected와 다르면 실패 처리
                # actual_tag가 비어 있으면 mismatch로 보지 않는다(점진 도입 호환성 고려).
                if actual_tag and actual_tag != expected_release_tag:
                    raise Exception(
                        f"releaseTag mismatch. expected={expected_release_tag}, actual={actual_tag}"
                    )
            else:
                try:
                    payload = json.loads(body) if body else {}
                except json.JSONDecodeError:
                    payload = {}

            # /ready 계약 강화:
            # 단순 200 응답이 아니라 필수 체크 항목이 true인지까지 확인한다.
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
        # 타임아웃, 연결 실패, 파싱 오류 등 나머지 예외
        raise Exception(f"/ready probe failed: {str(error)}")
