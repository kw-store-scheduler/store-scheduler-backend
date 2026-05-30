package com.example.store_scheduler_backend.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ScheduleAutomationService {

    private final ObjectMapper objectMapper;

    public Map<String, Object> runOptimization(Map<String, Object> configData) {
        try {
            // 1. 올려주신 사진 기준 파이썬 파일의 정확한 절대 경로
            String scriptPath = "C:\\Users\\aura230\\Desktop\\Store_Scheduler_Backend_New\\Store_Scheduler_Backend\\src\\main\\resources\\python\\scheduler_core_0527.py";

            // 2. 방금 터미널에서 확인한 완벽한 '진짜' 파이썬 경로 적용
            String pythonPath = "C:\\Program Files\\PyManager\\python.exe";

            // 3. 파이썬 프로세스 실행
            ProcessBuilder processBuilder = new ProcessBuilder(pythonPath, scriptPath);
            processBuilder.redirectErrorStream(true); // 에러 로그 통합 출력
            Process process = processBuilder.start();

            // 자바 -> 파이썬 JSON 전송
            String jsonInput = objectMapper.writeValueAsString(configData);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"))) {
                writer.write(jsonInput);
                writer.flush();
            }

            // 파이썬 -> 자바 결과 읽기
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                    System.out.println("[파이썬 실행 로그] : " + line); // 콘솔에서 진짜 파이썬이 도는지 확인
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("파이썬 엔진 내부 오류 (Exit Code: " + exitCode + ")\n출력 내용: " + output.toString());
            }

            return objectMapper.readValue(output.toString(), Map.class);

        } catch (Exception e) {
            // 시연용 가짜 코드를 지우고, 진짜 에러를 던지도록 정석으로 복구했습니다.
            throw new RuntimeException("스케줄 자동 생성 중 연동 에러 발생: " + e.getMessage(), e);
        }
    }
}