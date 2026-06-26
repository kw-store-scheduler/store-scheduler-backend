package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 회원가입 API
     */
    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
        authService.signup(request.getUsername(), request.getPassword(), request.getRole());
        return ResponseEntity.ok().build();
    }

    /**
     * 로그인 API — JWT 토큰 반환
     */
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(new TokenResponse(token));
    }

    @Data
    static class SignupRequest {
        @NotBlank(message = "아이디를 입력해주세요.")
        private String username;

        @NotBlank(message = "비밀번호를 입력해주세요.")
        private String password;

        @NotNull(message = "역할(MANAGER 또는 EMPLOYEE)을 입력해주세요.")
        private Role role;
    }

    @Data
    static class LoginRequest {
        @NotBlank(message = "아이디를 입력해주세요.")
        private String username;

        @NotBlank(message = "비밀번호를 입력해주세요.")
        private String password;
    }

    @Data
    @AllArgsConstructor
    static class TokenResponse {
        private String token;
    }
}
