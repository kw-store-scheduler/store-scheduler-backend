package com.example.store_scheduler_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CP-SAT 파이썬 엔진을 서브프로세스로 실행한다.
 *
 * 기존에는 "src/main/resources/python/..." 상대경로를 그대로 실행해서
 * 프로젝트 루트에서 bootRun 할 때만 동작하고 jar 배포 시에는 스크립트를 찾지 못했다.
 * 클래스패스의 스크립트를 기동 시점에 임시 파일로 꺼내 두고 그 경로로 실행한다.
 *
 * 입출력 규약: stdin으로 config JSON을 받고, stdout으로 결과 JSON 한 개를 출력한다.
 * stderr는 결과 파싱을 방해하지 않도록 따로 모아 로그와 오류 메시지에만 사용한다.
 */
@Slf4j
@Component
public class PythonEngineRunner {

    private final ObjectMapper objectMapper;
    private final String pythonCommand;
    private final long timeoutSeconds;
    private final Path scriptPath;

    public PythonEngineRunner(ObjectMapper objectMapper,
                              @Value("${scheduler.engine.script:python/scheduler_core_0527.py}") String scriptResource,
                              @Value("${scheduler.engine.python-command:python3}") String pythonCommand,
                              @Value("${scheduler.engine.timeout-seconds:60}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.pythonCommand = pythonCommand;
        this.timeoutSeconds = timeoutSeconds;
        this.scriptPath = extractScript(scriptResource);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> config) {
        Process process;
        try {
            process = new ProcessBuilder(pythonCommand, scriptPath.toString()).start();
        } catch (IOException e) {
            throw new IllegalStateException("파이썬 실행에 실패했습니다. scheduler.engine.python-command 설정을 확인해주세요: " + pythonCommand, e);
        }

        // stdout/stderr를 동시에 비워야 출력이 많을 때 파이프가 가득 차서 멈추지 않는다
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(objectMapper.writeValueAsBytes(config));
        } catch (IOException e) {
            process.destroyForcibly();
            throw new IllegalStateException("파이썬 엔진에 입력을 전달하지 못했습니다.", e);
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("파이썬 엔진이 " + timeoutSeconds + "초 안에 응답하지 않아 중단했습니다.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("파이썬 엔진 실행이 중단되었습니다.", e);
        }

        String out = stdout.join();
        String err = stderr.join();
        if (!err.isBlank()) {
            log.warn("[파이썬 엔진 stderr] {}", err.strip());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException("파이썬 엔진 오류 (exit code " + exitCode + "): " + err.strip());
        }

        try {
            return objectMapper.readValue(out, Map.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("파이썬 엔진 출력이 올바른 JSON이 아닙니다: " + out.strip(), e);
        }
    }

    private CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static Path extractScript(String scriptResource) {
        ClassPathResource resource = new ClassPathResource(scriptResource);
        try (InputStream in = resource.getInputStream()) {
            Path dir = Files.createTempDirectory("scheduler-engine");
            Path target = dir.resolve(Path.of(scriptResource).getFileName().toString());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("클래스패스에서 파이썬 엔진 스크립트를 찾을 수 없습니다: " + scriptResource, e);
        }
    }
}
