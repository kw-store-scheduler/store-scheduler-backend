package com.example.store_scheduler_backend.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 비밀번호 무작위 대입을 늦춘다. 서버가 한 대라 메모리로 충분하고, 재시작하면 초기화된다
@Component
public class LoginAttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public boolean isBlocked(String username) {
        Deque<Instant> attempts = failures.get(username);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            Instant limit = Instant.now().minus(WINDOW);
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(limit)) {
                attempts.pollFirst();
            }
            return attempts.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String username) {
        Deque<Instant> attempts = failures.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (attempts) {
            attempts.addLast(Instant.now());
        }
    }

    public void reset(String username) {
        failures.remove(username);
    }
}
