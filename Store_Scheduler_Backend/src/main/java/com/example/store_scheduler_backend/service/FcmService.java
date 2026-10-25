package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.NotificationRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Service
public class FcmService {

    private final NotificationRepository notificationRepository;

    public FcmService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Value("${fcm.credentials:}")
    private String credentialsJson;

    @Value("${fcm.credential-path:firebase-service-account.json}")
    private String credentialPath;

    private boolean enabled = false;

    @PostConstruct
    public void init() {
        try (InputStream credentials = openCredentials()) {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(credentials))
                        .build();
                FirebaseApp.initializeApp(options);
            }
            enabled = true;
        } catch (IOException e) {
            System.out.println("[FCM] 서비스 계정 키를 찾을 수 없어 푸시 알림이 비활성화됩니다.");
        }
    }

    // 배포 환경에서는 키 파일을 저장소에 둘 수 없어 환경변수 값을 우선한다
    private InputStream openCredentials() throws IOException {
        if (credentialsJson != null && !credentialsJson.isBlank()) {
            return new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8));
        }
        return new ClassPathResource(credentialPath).getInputStream();
    }

    // 알림함에 남기고, 사용자가 알림을 켜 두었으면 푸시도 보낸다
    public void send(User user, String title, String body) {
        if (user == null) {
            return;
        }
        // Firebase에도 Notification 클래스가 있어 전체 이름으로 쓴다
        notificationRepository.save(new com.example.store_scheduler_backend.domain.Notification(user, title, body));
        if (user.isNotificationsEnabled()) {
            sendToDevice(user.getDeviceToken(), title, body);
        }
    }

    public void sendToDevice(String deviceToken, String title, String body) {
        if (!enabled || deviceToken == null || deviceToken.isBlank()) {
            return;
        }
        Message message = Message.builder()
                .setToken(deviceToken)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();
        try {
            FirebaseMessaging.getInstance().send(message);
        } catch (FirebaseMessagingException e) {
            System.out.println("[FCM] 알림 전송 실패: " + e.getMessage());
        }
    }
}
