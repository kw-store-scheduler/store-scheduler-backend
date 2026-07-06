package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    // 로그인한 기기의 FCM 디바이스 토큰 등록/갱신
    @PatchMapping("/me/device-token")
    public ResponseEntity<Void> updateDeviceToken(
            @Valid @RequestBody DeviceTokenRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        authService.updateDeviceToken(userDetails.getUsername(), request.getDeviceToken());
        return ResponseEntity.ok().build();
    }

    @Data
    static class DeviceTokenRequest {
        @NotBlank(message = "디바이스 토큰을 입력해주세요.")
        private String deviceToken;
    }
}
