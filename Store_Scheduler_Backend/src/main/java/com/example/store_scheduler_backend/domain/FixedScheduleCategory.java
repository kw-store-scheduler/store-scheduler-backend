package com.example.store_scheduler_backend.domain;

// OTHER_JOB 시간은 주 근로시간 한도(52시간)에 합산한다
public enum FixedScheduleCategory {
    STUDY,
    OTHER_JOB,
    CARE,
    OTHER
}
