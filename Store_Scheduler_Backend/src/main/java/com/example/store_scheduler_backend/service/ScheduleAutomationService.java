package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
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
    private final FcmService fcmService;

    @Transactional
    public Map<String, Object> runOptimization(Long storeId, Map<String, Object> configData) {
        try {
            String scriptPath = "src/main/resources/python/scheduler_core_0527.py";
            String pythonPath = "python";

            ProcessBuilder processBuilder = new ProcessBuilder(pythonPath, scriptPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            String jsonInput = objectMapper.writeValueAsString(configData);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
                writer.write(jsonInput);
                writer.flush();
            }

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

            Map<String, Object> result = objectMapper.readValue(output.toString(), Map.class);
            String status = (String) result.get("status");

            if ("INFEASIBLE".equals(status)) {
                result.put("message", "현재 등록된 직원의 가용 시간으로는 스케줄을 생성할 수 없습니다. 조건을 완화해주세요.");
                result.put("success", false);
                return result;
            }

            if (result.containsKey("schedules")) {
                List<Map<String, Object>> scheduleList = (List<Map<String, Object>>) result.get("schedules");
                saveSchedulesToDB(storeId, scheduleList);
                notifyApprovedEmployees(storeId);
            }

            result.put("message", "스케줄 자동 생성 및 데이터베이스 저장 완료");
            result.put("success", true);
            return result;

        } catch (Exception e) {
            throw new RuntimeException("스케줄 자동 생성 중 연동 에러 발생: " + e.getMessage(), e);
        }
    }

    private void saveSchedulesToDB(Long storeId, List<Map<String, Object>> scheduleList) {
        for (Map<String, Object> schedMap : scheduleList) {
            String employeeName = (String) schedMap.get("employeeName");
            String dayOfWeekStr = (String) schedMap.get("dayOfWeek");
            String startTimeStr = (String) schedMap.get("startTime");
            String endTimeStr   = (String) schedMap.get("endTime");

            // 매장 내에서만 이름으로 검색해 크로스-매장 오염 방지
            Employee employee = employeeRepository.findByStoreIdAndName(storeId, employeeName)
                    .orElseThrow(() -> new RuntimeException("해당 직원을 DB에서 찾을 수 없습니다: " + employeeName));

            Schedule schedule = new Schedule();
            schedule.setEmployee(employee);
            schedule.setStore(employee.getStore());

            DayOfWeek targetDay = DayOfWeek.valueOf(dayOfWeekStr.toUpperCase());
            schedule.setWorkDate(LocalDate.now().with(TemporalAdjusters.nextOrSame(targetDay)));
            schedule.setStartTime(LocalTime.parse(startTimeStr));
            schedule.setEndTime(LocalTime.parse(endTimeStr));

            scheduleRepository.save(schedule);
        }
    }

    private void notifyApprovedEmployees(Long storeId) {
        List<Employee> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        for (Employee employee : employees) {
            fcmService.sendToDevice(
                    employee.getUser().getDeviceToken(),
                    "근무표 업데이트",
                    "새로운 근무표가 등록되었습니다. 확인해주세요."
            );
        }
    }
}
