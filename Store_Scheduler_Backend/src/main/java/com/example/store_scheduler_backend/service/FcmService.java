package com.example.store_scheduler_backend.service;

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

import java.io.IOException;
import java.io.InputStream;

@Service
public class FcmService {

    @Value("${fcm.credential-path:firebase-service-account.json}")
    private String credentialPath;

    private boolean enabled = false;

    @PostConstruct
    public void init() {
        try (InputStream credentials = new ClassPathResource(credentialPath).getInputStream()) {
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
