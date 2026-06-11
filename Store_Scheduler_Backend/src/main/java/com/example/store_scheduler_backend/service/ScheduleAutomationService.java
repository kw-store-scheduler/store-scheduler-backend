package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Schedule;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;

@Service
@RequiredArgsConstructor
public class ScheduleAutomationService {

    private final ObjectMapper objectMapper;
    private final ScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public Map<String, Object> runOptimization(Map<String, Object> configData) {
        try {
            // OS 환경 독립적인 파이썬 스크립트 상대 경로 설정
            String scriptPath = "src/main/resources/python/scheduler_core_0527.py";
            String pythonPath = "python";

            ProcessBuilder processBuilder = new ProcessBuilder(pythonPath, scriptPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // JSON 직렬화, Python 프로세스 입력 스트림 전달
            String jsonInput = objectMapper.writeValueAsString(configData);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
                writer.write(jsonInput);
                writer.flush();
            }

            // Python 프로세스 실행 결과 수신
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                    System.out.println("[파이썬 실행 로그] : " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("파이썬 엔진 내부 오류 (Exit Code: " + exitCode + ")\n출력 내용: " + output.toString());
            }

            // 결과 파싱 및 상태 코드 검증
            Map<String, Object> result = objectMapper.readValue(output.toString(), Map.class);
            String status = (String) result.get("status");

            if ("INFEASIBLE".equals(status)) {
                result.put("message", "현재 등록된 직원의 가용 시간으로는 스케줄을 생성할 수 없습니다. 조건을 완화해주세요.");
                result.put("success", false);
                return result;
            }

            // 최적화 성공 시 스케줄 DB 영속화
            if (result.containsKey("schedules")) {
                List<Map<String, Object>> scheduleList = (List<Map<String, Object>>) result.get("schedules");
                saveSchedulesToDB(scheduleList);
            }

            result.put("message", "스케줄 자동 생성 및 데이터베이스 저장 완료");
            result.put("success", true);

            return result;

        } catch (Exception e) {
            throw new RuntimeException("스케줄 자동 생성 중 연동 에러 발생: " + e.getMessage(), e);
        }
    }

    private void saveSchedulesToDB(List<Map<String, Object>> scheduleList) {
        for (Map<String, Object> schedMap : scheduleList) {
            String employeeName = (String) schedMap.get("employeeName");
            String dayOfWeekStr = (String) schedMap.get("dayOfWeek"); // ex) "MONDAY"
            String startTimeStr = (String) schedMap.get("startTime"); // ex) "09:00:00"
            String endTimeStr = (String) schedMap.get("endTime");     // ex) "13:00:00"

            Employee employee = employeeRepository.findByName(employeeName)
                    .orElseThrow(() -> new RuntimeException("해당 직원을 DB에서 찾을 수 없습니다: " + employeeName));

            Schedule schedule = new Schedule();
            schedule.setEmployee(employee);
            schedule.setStore(employee.getStore());

            DayOfWeek targetDay = DayOfWeek.valueOf(dayOfWeekStr.toUpperCase());
            LocalDate workDate = LocalDate.now().with(TemporalAdjusters.nextOrSame(targetDay));

            schedule.setWorkDate(workDate);
            schedule.setStartTime(LocalTime.parse(startTimeStr));
            schedule.setEndTime(LocalTime.parse(endTimeStr));

            scheduleRepository.save(schedule);
        }
    }
}