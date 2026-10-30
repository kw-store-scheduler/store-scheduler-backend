package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "인증 API", description = "회원가입·로그인(JWT 발급)")
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
    public ResponseEntity<AuthService.Tokens> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request.getUsername(), request.getPassword()));
    }

    /**
     * 액세스 토큰 재발급 API — 리프레시 토큰은 한 번만 쓸 수 있어 새 토큰으로 교체된다
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthService.Tokens> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @Data
    static class SignupRequest {
        @NotBlank(message = "아이디를 입력해주세요.")
        private String username;

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
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
    static class RefreshRequest {
        @NotBlank(message = "리프레시 토큰을 입력해주세요.")
        private String refreshToken;
    }
}
