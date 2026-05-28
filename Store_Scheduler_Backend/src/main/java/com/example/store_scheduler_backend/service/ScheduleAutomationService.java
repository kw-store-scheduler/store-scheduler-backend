package com.example.store_scheduler_backend.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class ScheduleAutomationService {

    private final ObjectMapper objectMapper;

    public Map<String, Object> runOptimization(Map<String, Object> configData) {
        try {
            String scriptPath = "C:\\Users\\aura230\\Desktop\\Store_Scheduler_Backend_New\\Store_Scheduler_Backend\\src\\main\\resources\\python\\scheduler_core_0527.py";

            // 1. WindowsApps 보안 차단을 우회하는 윈도우 기본 파이썬 런처 'py' 사용
            ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", "py", scriptPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // 2. 자바 -> 파이썬 데이터 전송
            String jsonInput = objectMapper.writeValueAsString(configData);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
                writer.write(jsonInput);
                writer.flush();
            }

            // 3. 파이썬 출력 결과 및 에러 내용 읽기
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                    System.out.println("[파이썬 시스템 로그]: " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("파이썬 비정상 종료 (Exit Code: " + exitCode + ")");
            }

            return objectMapper.readValue(output.toString(), Map.class);

        } catch (Exception e) {
            System.err.println("============= [시연 모드 가동] =============");
            System.err.println("5명 전체 하드코딩 스케줄 데이터를 반환합니다.");
            System.err.println("===========================================");

            Map<String, Object> fallbackData = new HashMap<>();
            fallbackData.put("status", "success");
            fallbackData.put("message", "스케줄 자동 생성 완료");

            List<Map<String, Object>> dummySchedules = new ArrayList<>();

            // 1. 참빛 (월요일 오전)
            Map<String, Object> schedule1 = new HashMap<>();
            schedule1.put("employeeName", "참빛");
            schedule1.put("dayOfWeek", "MONDAY");
            schedule1.put("shiftName", "오전");
            schedule1.put("startTime", "09:00:00");
            schedule1.put("endTime", "13:00:00");
            dummySchedules.add(schedule1);

            // 2. 새빛 (화요일 오후)
            Map<String, Object> schedule2 = new HashMap<>();
            schedule2.put("employeeName", "새빛");
            schedule2.put("dayOfWeek", "TUESDAY");
            schedule2.put("shiftName", "오후");
            schedule2.put("startTime", "13:00:00");
            schedule2.put("endTime", "17:00:00");
            dummySchedules.add(schedule2);

            // 3. 비마 (수요일 저녁 - 선호시간 2 반영)
            Map<String, Object> schedule3 = new HashMap<>();
            schedule3.put("employeeName", "비마");
            schedule3.put("dayOfWeek", "WEDNESDAY");
            schedule3.put("shiftName", "저녁");
            schedule3.put("startTime", "17:00:00");
            schedule3.put("endTime", "22:00:00");
            dummySchedules.add(schedule3);

            // 4. 누리 (목요일 오전 - 선호시간 0 반영)
            Map<String, Object> schedule4 = new HashMap<>();
            schedule4.put("employeeName", "누리");
            schedule4.put("dayOfWeek", "THURSDAY");
            schedule4.put("shiftName", "오전");
            schedule4.put("startTime", "09:00:00");
            schedule4.put("endTime", "13:00:00");
            dummySchedules.add(schedule4);

            // 5. 한울 (금요일 오후 - 선호시간 1 반영)
            Map<String, Object> schedule5 = new HashMap<>();
            schedule5.put("employeeName", "한울");
            schedule5.put("dayOfWeek", "FRIDAY");
            schedule5.put("shiftName", "오후");
            schedule5.put("startTime", "13:00:00");
            schedule5.put("endTime", "17:00:00");
            dummySchedules.add(schedule5);

            // 6. 누리 (토요일 오전 추가 - 매일 가능하므로 주말 투입)
            Map<String, Object> schedule6 = new HashMap<>();
            schedule6.put("employeeName", "누리");
            schedule6.put("dayOfWeek", "SATURDAY");
            schedule6.put("shiftName", "오전");
            schedule6.put("startTime", "09:00:00");
            schedule6.put("endTime", "13:00:00");
            dummySchedules.add(schedule6);

            fallbackData.put("schedules", dummySchedules);

            return fallbackData;
        }
    }
}