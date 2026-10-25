package com.example.store_scheduler_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // 배포된 웹 프론트 주소 등 추가로 허용할 출처 (쉼표 구분)
    @Value("${cors.allowed-origins:}")
    private String[] extraAllowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] patterns = new String[extraAllowedOrigins.length + 2];
        // Flutter 웹 디버그 실행은 매번 다른 localhost 포트를 쓴다
        patterns[0] = "http://localhost:*";
        patterns[1] = "http://127.0.0.1:*";
        System.arraycopy(extraAllowedOrigins, 0, patterns, 2, extraAllowedOrigins.length);
        registry.addMapping("/**") // 모든 API 주소
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") // 허용할 HTTP 메서드
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    // 서버 주소만 입력해도 API 문서로 이동
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/swagger-ui/index.html");
    }
}