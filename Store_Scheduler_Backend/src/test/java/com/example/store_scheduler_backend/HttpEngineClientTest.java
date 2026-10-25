package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.service.HttpEngineClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 엔진 서버(POST /solve) 호출 규약 검증. 실제 엔진 서버 대신 로컬 HTTP 서버로 응답을 흉내 낸다.
 */
class HttpEngineClientTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"status\":\"OPTIMAL\",\"schedule\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/solve", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Engine-Token"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpEngineClient client(String token) {
        // 끝에 /가 붙은 주소도 처리되는지 함께 확인
        return new HttpEngineClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/", token, 5);
    }

    @Test
    void config를_JSON으로_보내고_토큰_헤더를_붙여_결과를_돌려준다() {
        Map<String, Object> result = client("secret").run(Map.of("num_days", 7));

        assertThat(result.get("status")).isEqualTo("OPTIMAL");
        assertThat(objectMapper.readValue(receivedBody.get(), Map.class)).isEqualTo(Map.of("num_days", 7));
        assertThat(receivedToken.get()).isEqualTo("secret");
    }

    @Test
    void 엔진_서버가_오류를_돌려주면_상태코드와_내용을_담아_실패한다() {
        status = 401;
        responseBody = "{\"detail\":\"invalid engine token\"}";

        assertThatThrownBy(() -> client("wrong").run(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("invalid engine token");
    }

    @Test
    void 엔진_서버에_연결할_수_없으면_주소를_담아_실패한다() {
        HttpEngineClient unreachable = new HttpEngineClient(objectMapper, "http://127.0.0.1:1", "", 5);

        assertThatThrownBy(() -> unreachable.run(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("엔진 서버에 연결하지 못했습니다");
    }
}
