"""
매장 근무 스케줄 자동 생성 — 핵심 최적화 엔진
백엔드(Spring Boot) 연동용

사용법:
    result = solve_schedule(config)
    # result["schedule"] → 스케줄 배열
    # result["cost"]     → 총 인건비 (원)
    # result["status"]   → "OPTIMAL" / "FEASIBLE" / "INFEASIBLE"
"""

from ortools.sat.python import cp_model
import time


def solve_schedule(config: dict) -> dict:
    """
    CP-SAT 최적 스케줄 생성 함수 — 백엔드 호출 진입점

    Parameters
    ----------
    config : dict
        {
          "employees": [
              {
                "id": "emp_001",
                "name": "참빛",
                "available_days": [0, 1, 2, 3, 4],   # 0=월 ~ 6=일
                "preferred_shifts": [0, 1]             # 선호 교대 인덱스
              },
              ...
          ],
          "shifts": [
              {"name": "오전", "hours": 4, "is_night": False},
              {"name": "오후", "hours": 4, "is_night": False},
              {"name": "저녁", "hours": 4, "is_night": True},
          ],
          "target_staff":  [2, 3, 2],   # 교대별 목표 인원 (소프트 제약)
          "min_staff":     [1, 1, 1],   # 교대별 최소 인원 (하드 제약)
          "base_hourly":   9860,        # 기본 시급 (원)
          "night_bonus":   0.5,         # 야간수당 비율 (0.5 = 50%)
          "time_limit":    10           # 최대 풀이 시간 (초)
        }

    Returns
    -------
    dict
        {
          "status":    "OPTIMAL" | "FEASIBLE" | "INFEASIBLE",
          "schedule":  [                     # None if INFEASIBLE
              {
                "employee_id": "emp_001",
                "assignments": [
                    {"day": 0, "shift": 1}, # 월요일 오후 교대
                    ...
                ]
              },
              ...
          ],
          "cost":       966280,   # 총 인건비 (원), None if INFEASIBLE
          "solve_ms":   619       # 풀이 시간 (ms)
        }
    """

    # ── 입력값 파싱 ──────────────────────────────
    employees  = config["employees"]
    shifts     = config["shifts"]
    target_s   = config["target_staff"]   # 교대별 목표 인원
    min_s      = config["min_staff"]      # 교대별 최소 인원
    base_hw    = config["base_hourly"]
    night_rate = config["night_bonus"]
    time_limit = config.get("time_limit", 10)

    E = len(employees)   # 직원 수
    D = 7                # 요일 수 (항상 7일 고정)
    S = len(shifts)      # 교대 수

    model  = cp_model.CpModel()
    solver = cp_model.CpSolver()

    # ── 결정변수: work[e][d][s] ∈ {0, 1} ────────
    # work[e,d,s] = 1 이면 직원 e가 요일 d에 교대 s 근무
    work = {
        (e, d, s): model.NewBoolVar(f"w_{e}_{d}_{s}")
        for e in range(E)
        for d in range(D)
        for s in range(S)
    }

    # ── [하드 1] 가용 요일 외 근무 불가 ──────────
    for e, emp in enumerate(employees):
        avail = set(emp["available_days"])
        for d in range(D):
            if d not in avail:
                for s in range(S):
                    model.Add(work[e, d, s] == 0)

    # ── [하드 2] 하루 최대 1교대 (이중 근무 금지) ─
    for e in range(E):
        for d in range(D):
            model.AddAtMostOne(work[e, d, s] for s in range(S))

    # ── [하드 3] 주 52시간 초과 금지 (근로기준법) ─
    for e in range(E):
        weekly_hours = sum(
            work[e, d, s] * shifts[s]["hours"]
            for d in range(D)
            for s in range(S)
        )
        model.Add(weekly_hours <= 52)

    # ── [하드 4] 연속 5일 이상 근무 금지 ─────────
    for e in range(E):
        for d in range(D - 4):
            model.Add(
                sum(work[e, d + k, s]
                    for k in range(5)
                    for s in range(S)) <= 4
            )

    # ── [하드 5] 교대별 최소 인원 보장 ───────────
    for d in range(D):
        for s in range(S):
            model.Add(
                sum(work[e, d, s] for e in range(E)) >= min_s[s]
            )

    # ── [소프트 1] 목표 인원 미달 패널티 ─────────
    # 인원 부족 1명당 5,000,000 패널티 (운영 차질 방지)
    UNDERSTAFF_W = 5_000_000
    understaff_vars = []

    for d in range(D):
        for s in range(S):
            actual = sum(work[e, d, s] for e in range(E))
            target = target_s[s]
            for deficit in range(1, target + 1):
                v = model.NewBoolVar(f"us_{d}_{s}_{deficit}")
                model.Add(actual <= deficit - 1).OnlyEnforceIf(v)
                model.Add(actual >= deficit).OnlyEnforceIf(v.Not())
                understaff_vars.append(v)

    # ── [소프트 2] 주 15시간 미만 패널티 (주휴수당) ─
    # 주 15시간 이상 근무 시 주휴수당 발생 → 15시간 미달 패널티
    LOW_HOURS_W = 2_000_000
    low_hours_vars = []

    for e in range(E):
        hours = sum(
            work[e, d, s] * shifts[s]["hours"]
            for d in range(D)
            for s in range(S)
        )
        v = model.NewBoolVar(f"lh_{e}")
        model.Add(hours >= 15).OnlyEnforceIf(v.Not())
        model.Add(hours < 15).OnlyEnforceIf(v)
        low_hours_vars.append(v)

    # ── [소프트 3] 선호 교대 외 배정 패널티 ──────
    PREF_W = 100_000
    pref_vars = []

    for e, emp in enumerate(employees):
        pref = set(emp.get("preferred_shifts", []))
        for d in range(D):
            for s in range(S):
                if s not in pref:
                    v = model.NewBoolVar(f"pf_{e}_{d}_{s}")
                    model.Add(v == work[e, d, s])
                    pref_vars.append(v)

    # ── 목적함수: 인건비 + 야간수당 + 패널티 최소화 ─
    labor = sum(
        work[e, d, s] * shifts[s]["hours"] * base_hw
        for e in range(E)
        for d in range(D)
        for s in range(S)
    )
    night = sum(
        work[e, d, s] * shifts[s]["hours"] * int(base_hw * night_rate)
        for e in range(E)
        for d in range(D)
        for s in range(S)
        if shifts[s]["is_night"]
    )

    model.Minimize(
        labor + night
        + UNDERSTAFF_W * sum(understaff_vars)
        + LOW_HOURS_W  * sum(low_hours_vars)
        + PREF_W       * sum(pref_vars)
    )

    # ── 솔버 실행 ────────────────────────────────
    solver.parameters.max_time_in_seconds = time_limit
    solver.parameters.log_search_progress = False

    t0 = time.time()
    status = solver.Solve(model)
    solve_ms = int((time.time() - t0) * 1000)

    STATUS_MAP = {
        cp_model.OPTIMAL:    "OPTIMAL",
        cp_model.FEASIBLE:   "FEASIBLE",
        cp_model.INFEASIBLE: "INFEASIBLE",
        cp_model.UNKNOWN:    "UNKNOWN",
    }

    # ── 결과 추출 ────────────────────────────────
    if status not in [cp_model.OPTIMAL, cp_model.FEASIBLE]:
        return {
            "status":   STATUS_MAP.get(status, "UNKNOWN"),
            "schedule": None,
            "cost":     None,
            "solve_ms": solve_ms,
        }

    schedule = []
    total_cost = 0

    for e, emp in enumerate(employees):
        assignments = []
        for d in range(D):
            for s in range(S):
                if solver.Value(work[e, d, s]):
                    assignments.append({"day": d, "shift": s})
                    h = shifts[s]["hours"]
                    total_cost += h * base_hw
                    if shifts[s]["is_night"]:
                        total_cost += int(h * base_hw * night_rate)
        schedule.append({
            "employee_id":  emp["id"],
            "assignments":  assignments,
        })

    return {
        "status":   STATUS_MAP[status],
        "schedule": schedule,
        "cost":     total_cost,
        "solve_ms": solve_ms,
    }


# ─────────────────────────────────────────
# 호출 예시 (백엔드 연동 테스트용)
# ─────────────────────────────────────────

if __name__ == "__main__":
    import sys
    import json

    try:
        # 자바(스프링 부트)가 프로세스 스트림으로 쏴준 진짜 JSON 데이터를 읽어옴
        input_data = sys.stdin.read()
        config = json.loads(input_data)

        # 최적화 알고리즘 엔진 가동
        result = solve_schedule(config)

        # 결과를 다시 자바 백엔드가 읽을 수 있게 JSON 문자열로 출력
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        error_result = {"status": "INFEASIBLE", "error": str(e)}
        print(json.dumps(error_result, ensure_ascii=False))
