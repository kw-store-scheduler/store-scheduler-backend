package com.example.store_scheduler_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

// 엔진 서버(engine-server, FastAPI)의 POST /solve로 엔진을 호출한다. 입력·출력 JSON은 서브프로세스 방식과 같다
@Component
@ConditionalOnExpression("!'${scheduler.engine.url:}'.isEmpty()")
public class HttpEngineClient implements ScheduleEngine {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI solveUri;
    private final String token;
    private final Duration timeout;

    public HttpEngineClient(ObjectMapper objectMapper,
                            @Value("${scheduler.engine.url}") String engineUrl,
                            @Value("${scheduler.engine.token:}") String token,
                            @Value("${scheduler.engine.timeout-seconds:60}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.solveUri = URI.create(engineUrl.replaceAll("/+$", "") + "/solve");
        this.token = token;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> config) {
        HttpRequest request = HttpRequest.newBuilder(solveUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Engine-Token", token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(config)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("엔진 서버가 " + timeout.toSeconds() + "초 안에 응답하지 않았습니다.", e);
        } catch (IOException e) {
            throw new IllegalStateException("엔진 서버에 연결하지 못했습니다: " + solveUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("엔진 서버 호출이 중단되었습니다.", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("엔진 서버 오류 (HTTP " + response.statusCode() + "): " + response.body());
        }
        try {
            return objectMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("엔진 서버 응답이 올바른 JSON이 아닙니다: " + response.body(), e);
        }
    }
}
