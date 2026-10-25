package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 직원이 한 달 치 희망을 제출한 기록과 개인 근무 규칙. 항목이 null이면 그 규칙은 쓰지 않는다
@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "target_month"}))
public class PreferenceSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    // 해당 월 1일. month는 H2 예약어라 컬럼 이름을 바꾼다
    @Column(name = "target_month", nullable = false)
    private LocalDate month;

    private Integer maxWeekendShifts;

    private Integer minMonthlyHours;

    private Integer maxMonthlyHours;

    private Integer maxConsecutiveDays;

    private boolean noOpenAfterClose;

    // PreferencePriority 이름을 우선순위 순서대로 쉼표로 이어 저장
    private String priorities;

    // 정규직 교대 선호. "교대id:PREFER" 형식을 쉼표로 이어 저장
    private String shiftAttitudes;

    // 정규직: 주마다 쉬는 날을 이틀 붙여서
    private Boolean restDaysTogether;

    // 이번 달 최대 시간이 계약 상한을 넘을 때 점주가 승인했는지. null이면 승인 전
    private Boolean contractExceedApproved;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    public PreferenceSubmission(Employee employee, LocalDate month) {
        this.employee = employee;
        this.month = month;
    }

    public void update(Integer maxWeekendShifts, Integer minMonthlyHours, Integer maxMonthlyHours,
                       Integer maxConsecutiveDays, boolean noOpenAfterClose, List<PreferencePriority> priorities) {
        this.maxWeekendShifts = maxWeekendShifts;
        this.minMonthlyHours = minMonthlyHours;
        this.maxMonthlyHours = maxMonthlyHours;
        this.maxConsecutiveDays = maxConsecutiveDays;
        this.noOpenAfterClose = noOpenAfterClose;
        this.priorities = priorities.stream().map(Enum::name).collect(Collectors.joining(","));
        this.submittedAt = LocalDateTime.now();
    }

    public void updateFullTimeOptions(Map<Long, ShiftAttitude> attitudes, boolean restDaysTogether) {
        this.shiftAttitudes = attitudes.isEmpty() ? null : attitudes.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().name())
                .collect(Collectors.joining(","));
        this.restDaysTogether = restDaysTogether;
    }

    public Map<Long, ShiftAttitude> getShiftAttitudeMap() {
        Map<Long, ShiftAttitude> map = new LinkedHashMap<>();
        if (shiftAttitudes != null && !shiftAttitudes.isBlank()) {
            for (String part : shiftAttitudes.split(",")) {
                String[] kv = part.split(":");
                map.put(Long.valueOf(kv[0]), ShiftAttitude.valueOf(kv[1]));
            }
        }
        return map;
    }

    public boolean isRestDaysTogether() {
        return Boolean.TRUE.equals(restDaysTogether);
    }

    public void decideContractExceed(boolean approved) {
        this.contractExceedApproved = approved;
    }

    public List<PreferencePriority> getPriorityList() {
        if (priorities == null || priorities.isBlank()) {
            return List.of();
        }
        return Arrays.stream(priorities.split(",")).map(PreferencePriority::valueOf).toList();
    }
}
