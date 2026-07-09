package com.example.store_scheduler_backend.config;

import com.example.store_scheduler_backend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
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
                        .requestMatchers("/api/stores/*/schedules").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}