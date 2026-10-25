package com.example.store_scheduler_backend.config;

import com.example.store_scheduler_backend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // WebConfig의 CORS 설정을 Security 필터에도 적용 (없으면 브라우저 preflight가 인증 단계에서 403)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/stores/join/**").hasRole("EMPLOYEE")
                        .requestMatchers("/api/availabilities/my", "/api/availabilities", "/api/availabilities/**").hasRole("EMPLOYEE")
                        .requestMatchers("/api/stores").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/employees/**").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/stores/*/skills").authenticated()
                        .requestMatchers("/api/stores/*/skills/**").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/availabilities").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/payroll").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/shifts").authenticated()
                        .requestMatchers("/api/stores/*/schedules/automate").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/schedules/proposals/**").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/schedules").authenticated()
                        .requestMatchers("/api/stores/*/home/owner").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/home/worker").hasRole("EMPLOYEE")
                        .requestMatchers("/api/stores/*/attendance/clock-in", "/api/stores/*/attendance/clock-out",
                                "/api/stores/*/attendance/my").hasRole("EMPLOYEE")
                        .requestMatchers("/api/stores/*/attendance").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/preferences/my", "/api/stores/*/preferences/my/**",
                                "/api/stores/*/unavailable-periods/my", "/api/stores/*/fixed-schedules/my",
                                "/api/stores/*/preference-report/my").hasRole("EMPLOYEE")
                        .requestMatchers("/api/stores/*/preferences").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/tasks/my").hasRole("EMPLOYEE")
                        .requestMatchers("/api/stores/*/my-skills").hasRole("EMPLOYEE")
                        .requestMatchers("/api/stores/*/skill-requests", "/api/stores/*/skill-matrix").hasRole("MANAGER")
                        .requestMatchers("/api/stores/*/leaves/my").hasRole("EMPLOYEE")
                        .requestMatchers(HttpMethod.GET, "/api/stores/*/leaves").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/stores/*/leaves").hasRole("EMPLOYEE")
                        .anyRequest().authenticated()
                )
                // 앱이 "다시 로그인"(401)과 "권한 없음"(403)을 구분할 수 있게 둘 다 JSON 메시지로 응답
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다. 토큰이 없거나 만료되었습니다."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "접근 권한이 없습니다.")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}