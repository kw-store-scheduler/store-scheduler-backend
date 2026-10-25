"""
Spring Boot ↔ 최적화 엔진 연결용 진입점

stdin으로 config JSON을 받아 scheduler_core.solve_schedule()을 실행하고,
결과 JSON 한 개를 stdout으로 출력한다.
엔진(scheduler_core.py)은 수정 없이 그대로 두고, JSON으로 표현할 수 없는 입력만 여기서 변환한다.
scheduler_core.py는 store-scheduler-da 레포의 scheduler_core_0527.py와 같은 파일이다. 엔진을 고칠 때는 DA 레포를 먼저 수정한다.

- prev_assignments: JSON 배열 [{"employee_id", "day", "shift"}] → 엔진이 받는 {(employee_id, day): shift}
- max_alt_solutions: solve_schedule()의 인자로 전달 (기본 3)

예외가 나면 traceback이 stderr로 출력되고 exit code 1로 종료된다.
엔진 서버(engine-server/app.py)도 같은 solve()를 쓴다.
"""

import json
import sys

from scheduler_core import solve_schedule


def solve(config):
    prev = config.get("prev_assignments")
    if isinstance(prev, list):
        config["prev_assignments"] = {(p["employee_id"], p["day"]): p["shift"] for p in prev}

    max_alt = config.pop("max_alt_solutions", 3)
    return solve_schedule(config, max_alt_solutions=max_alt)


def main():
    result = solve(json.loads(sys.stdin.read()))
    sys.stdout.write(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
