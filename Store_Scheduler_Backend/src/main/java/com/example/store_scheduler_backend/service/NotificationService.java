package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Notification;
import com.example.store_scheduler_backend.repository.NotificationRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NotificationService {

    private static final int MAX_LIMIT = 100;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public List<Notification> findMine(String username, int limit) {
        return notificationRepository.findByUser_UsernameOrderByCreatedAtDescIdDesc(username,
                PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LIMIT))));
    }

    public long countUnread(String username) {
        return notificationRepository.countByUser_UsernameAndReadAtIsNull(username);
    }

    @Transactional
    public Notification markRead(String username, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(n -> n.getUser().getUsername().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 알림입니다."));
        notification.markRead();
        return notification;
    }

    @Transactional
    public int markAllRead(String username) {
        List<Notification> unread = notificationRepository.findByUser_UsernameAndReadAtIsNull(username);
        unread.forEach(Notification::markRead);
        return unread.size();
    }

    @Transactional
    public boolean updatePushEnabled(String username, boolean enabled) {
        userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."))
                .updateNotificationsEnabled(enabled);
        return enabled;
    }
}
