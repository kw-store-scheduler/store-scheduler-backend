package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Industry;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.service.AuthService;
import com.example.store_scheduler_backend.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@Tag(name = "사용자 API", description = "내 정보·역할 선택, 푸시 알림 기기 토큰 등록")
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final NotificationService notificationService;

    // 알림함 (최신순, 기본 50개)
    @GetMapping("/me/notifications")
    public ResponseEntity<NotificationsResponse> getNotifications(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal UserDetails userDetails) {
        String username = userDetails.getUsername();
        return ResponseEntity.ok(new NotificationsResponse(notificationService.countUnread(username),
                notificationService.findMine(username, limit).stream()
                        .map(n -> new NotificationResponse(n.getId(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.isRead()))
                        .toList()));
    }

    @PostMapping("/me/notifications/{notificationId}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal UserDetails userDetails) {
        notificationService.markRead(userDetails.getUsername(), notificationId);
        return ResponseEntity.ok().build();
    }

    // 모두 읽음
    @PostMapping("/me/notifications/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal UserDetails userDetails) {
        notificationService.markAllRead(userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    // 푸시 알림 켜기·끄기 (꺼도 알림함에는 쌓인다)
    @PatchMapping("/me/notification-settings")
    public ResponseEntity<NotificationSettingsResponse> updateNotificationSettings(
            @RequestBody NotificationSettingsRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(new NotificationSettingsResponse(notificationService.updatePushEnabled(
                userDetails.getUsername(), !Boolean.FALSE.equals(request.getPushEnabled()))));
    }

    // 로그인 직후 어느 화면으로 보낼지 정하는 용도: 역할, 소유 매장, 합류한 매장과 승인 상태
    @GetMapping("/me")
    public ResponseEntity<MeResponse> getMe(@AuthenticationPrincipal UserDetails userDetails) {
        AuthService.Me me = authService.getMe(userDetails.getUsername());
        return ResponseEntity.ok(new MeResponse(
                me.user().getId(),
                me.user().getUsername(),
                me.user().getRole(),
                me.ownedStores().stream()
                        .map(s -> new OwnedStore(s.getId(), s.getName(), s.getIndustry(), s.getStoreCode()))
                        .toList(),
                me.memberships().stream()
                        .map(e -> new Membership(e.getId(), e.getStore().getId(), e.getStore().getName(), e.getName(),
                                e.getDisplayName(), e.getStatus().name(), e.getEmploymentType()))
                        .toList()));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody PasswordRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        authService.changePassword(userDetails.getUsername(), request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    // 역할을 바꾸면 새 토큰을 돌려준다
    @PatchMapping("/me/role")
    public ResponseEntity<TokenResponse> changeRole(
            @Valid @RequestBody RoleRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(new TokenResponse(authService.changeRole(userDetails.getUsername(), request.getRole())));
    }

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

    @Data
    static class PasswordRequest {
        @NotBlank(message = "현재 비밀번호를 입력해주세요.")
        private String currentPassword;
        @NotBlank(message = "새 비밀번호를 입력해주세요.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        private String newPassword;
    }

    @Data
    static class RoleRequest {
        @NotNull(message = "역할(MANAGER 또는 EMPLOYEE)을 입력해주세요.")
        private Role role;
    }

    record TokenResponse(String token) {
    }

    @Data
    static class NotificationSettingsRequest {
        private Boolean pushEnabled;
    }

    record NotificationSettingsResponse(boolean pushEnabled) {
    }

    record NotificationResponse(Long id, String title, String body, LocalDateTime createdAt, boolean read) {
    }

    record NotificationsResponse(long unreadCount, List<NotificationResponse> notifications) {
    }

    record OwnedStore(Long id, String name, Industry industry, String storeCode) {
    }

    record Membership(Long employeeId, Long storeId, String storeName, String name, String displayName, String status,
                      EmploymentType employmentType) {
    }

    record MeResponse(Long id, String username, Role role, List<OwnedStore> ownedStores, List<Membership> memberships) {
    }
}
