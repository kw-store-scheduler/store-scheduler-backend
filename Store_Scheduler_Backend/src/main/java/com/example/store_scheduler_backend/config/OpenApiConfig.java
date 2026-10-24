package com.example.store_scheduler_backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Swagger UI의 Authorize 버튼에 로그인 API로 받은 JWT를 넣으면 인증이 필요한 API도 바로 호출할 수 있다
@Configuration
public class OpenApiConfig {

    private static final String JWT_SCHEME = "JWT";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("Store Scheduler API").version("v1"))
                .components(new Components().addSecuritySchemes(JWT_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(JWT_SCHEME));
    }
}
