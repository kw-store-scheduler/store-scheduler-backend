package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users") // user는 PostgreSQL 예약어
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String deviceToken;

    // 푸시 알림 받기. 기존 데이터 때문에 null 허용, null은 켜짐
    private Boolean notificationsEnabled;

    public User(String username, String password, Role role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    public boolean isNotificationsEnabled() {
        return !Boolean.FALSE.equals(notificationsEnabled);
    }

    public void updateNotificationsEnabled(boolean enabled) {
        this.notificationsEnabled = enabled;
    }

    public void updateDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }
}
