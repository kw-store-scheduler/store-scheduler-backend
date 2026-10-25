package com.example.store_scheduler_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 대타 요청 만료(30분), 지난 결원 정리와 약속 점수 차감, 매달 약속 점수 회복, 근무표 자동 생성, 희망 제출 마감 알림
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scheduler.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    private final SwapRequestService swapRequestService;
    private final SchedulePlanService schedulePlanService;
    private final PreferenceService preferenceService;

    @Scheduled(fixedDelay = 60_000)
    public void expireStaleOffers() {
        swapRequestService.expireStaleOffers(LocalDateTime.now());
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void settlePastRequests() {
        swapRequestService.settlePastRequests(LocalDate.now());
    }

    @Scheduled(cron = "0 20 0 1 * *", zone = "Asia/Seoul")
    public void recoverPromiseScores() {
        swapRequestService.recoverPromiseScores();
    }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Seoul")
    public void remindPreferenceDeadlines() {
        preferenceService.remindDeadlines(LocalDate.now());
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void generateDueSchedules() {
        schedulePlanService.generateDue(LocalDateTime.now());
    }
}
