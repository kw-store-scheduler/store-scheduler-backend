package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.config.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 예기치_못한_예외는_500으로_응답하고_원인을_로그에_남긴다(CapturedOutput output) {
        var response = handler.handleUnexpected(new IllegalStateException("엔진 연결 실패"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().get("message")).doesNotContain("엔진");
        assertThat(output).contains("엔진 연결 실패");
    }

    @Test
    void DB_제약_위반은_409로_응답한다() {
        var response = handler.handleDataIntegrity(new DataIntegrityViolationException("duplicate key"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }
}
