package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.service.PythonEngineRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 파이썬 서브프로세스 실행 규약(stdin JSON → stdout JSON) 검증.
 * 로컬에 python3가 없으면 건너뛴다.
 */
class PythonEngineRunnerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void requirePython() {
        assumeTrue(commandSucceeds("python3", "--version"), "python3가 없어 건너뜀");
    }

    private PythonEngineRunner runner(String script, long timeoutSeconds) {
        return new PythonEngineRunner(objectMapper, "python-fixtures", script, "python3", timeoutSeconds);
    }

    @Test
    void stdin으로_보낸_config를_받아_stdout의_JSON을_결과로_돌려준다() {
        Map<String, Object> result = runner("echo.py", 10).run(Map.of("name", "오픈"));

        assertThat(result.get("status")).isEqualTo("OPTIMAL");
        assertThat(result.get("echo")).isEqualTo(Map.of("name", "오픈"));
    }

    @Test
    void 제한_시간을_넘기면_프로세스를_중단하고_예외를_던진다() {
        assertThatThrownBy(() -> runner("slow.py", 1).run(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1초 안에 응답하지 않아");
    }

    @Test
    void 비정상_종료하면_stderr_내용을_포함해_예외를_던진다() {
        assertThatThrownBy(() -> runner("fail.py", 10).run(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit code 2")
                .hasMessageContaining("boom");
    }

    @Test
    void 존재하지_않는_스크립트는_생성_시점에_실패한다() {
        assertThatThrownBy(() -> runner("missing.py", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing.py");
    }

    @Test
    void 운영_엔진을_클래스패스에서_꺼내_실제로_실행한다() {
        assumeTrue(commandSucceeds("python3", "-c", "import ortools"), "ortools가 없어 건너뜀");

        // 직원은 1명뿐인데 매일 최소 1명이 필요 → 구 엔진은 INFEASIBLE, 현 엔진은 근무표 + 부족 현황 반환
        Map<String, Object> config = Map.of(
                "employees", List.of(
                        Map.of("id", "emp_1", "name", "직원1", "available_days", List.of(0, 1, 2), "preferred_shifts", List.of(0))),
                "shifts", List.of(Map.of("name", "오픈", "hours", 4, "is_night", false)),
                "target_staff", List.of(1),
                "min_staff", List.of(1),
                "base_hourly", 10030,
                "night_bonus", 0.5,
                "time_limit", 5,
                "prev_assignments", List.of(Map.of("employee_id", "emp_1", "day", 0, "shift", 0)));

        PythonEngineRunner engine = new PythonEngineRunner(objectMapper, "python", "run_engine.py", "python3", 30);
        Map<String, Object> result = engine.run(config);

        assertThat(result.get("status")).isIn("OPTIMAL", "FEASIBLE");
        assertThat(result.get("schedule")).asList().hasSize(1);
        assertThat(result.get("shortage_report")).asList().isNotEmpty();
    }

    private static boolean commandSucceeds(String... command) {
        try {
            return new ProcessBuilder(command).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
