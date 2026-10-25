package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// 결원 근무를 대신할 수 있는 직원을 근무표 생성과 같은 규칙(휴무 신청, 가용 시간, 하루 1교대, 주 52시간, 연속 5일 금지)으로 고르고, 결원으로 모자라게 된 스킬 보유자를 앞세운다
@Component
@RequiredArgsConstructor
public class SubstituteFinder {

    private static final int MAX_WEEKLY_HOURS = 52;
    private static final int MAX_CONSECUTIVE_DAYS = 4;

    private final EmployeeRepository employeeRepository;
    private final AvailabilityRepository availabilityRepository;
    private final ScheduleRepository scheduleRepository;
    private final DayOffRequestRepository dayOffRequestRepository;
    private final ShiftSkillRequirementRepository requirementRepository;

    // onPreferredDayOff: 희망 휴무를 신청한 날이라 가능하지만 뒤로 미룸, coveredSkills: 결원으로 모자라게 된 스킬 중 보유한 것
    public record Candidate(Employee employee, boolean eligible, boolean onPreferredDayOff, int weeklyHours,
                            List<String> coveredSkills, List<String> reasons) {
    }

    // 근무 한 칸. 확정 근무표뿐 아니라 아직 확정 전인 후보안의 배치도 같은 규칙으로 검사하기 위해 쓴다
    public record WorkSlot(Employee employee, LocalDate date, LocalTime start, LocalTime end) {
        static WorkSlot of(Schedule schedule) {
            return new WorkSlot(schedule.getEmployee(), schedule.getWorkDate(), schedule.getStartTime(), schedule.getEndTime());
        }
    }

    // 가능한 직원은 부족 스킬 보유 → 희망 휴무 아님 → 이번 주 근무 시간이 적은 순 → 시급이 낮은 순, 불가능한 직원은 그 뒤에 사유와 함께
    public List<Candidate> find(Long storeId, LocalDate date, LocalTime start, LocalTime end, Long excludeEmployeeId) {
        return rank(storeId, date, start, end, excludeEmployeeId, loadSlots(storeId, date, null, List.of()));
    }

    // 후보안 검토용: 후보안이 다루는 주는 확정 근무표 대신 후보안 배치를 기준으로 판단한다
    public List<Candidate> findForProposal(Long storeId, LocalDate date, LocalTime start, LocalTime end, Long excludeEmployeeId,
                                           LocalDate proposalWeekStart, List<WorkSlot> proposedWeek) {
        return rank(storeId, date, start, end, excludeEmployeeId, loadSlots(storeId, date, proposalWeekStart, proposedWeek));
    }

    private Map<Long, List<WorkSlot>> loadSlots(Long storeId, LocalDate date, LocalDate proposalWeekStart, List<WorkSlot> proposedWeek) {
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = weekStart.isBefore(date.minusDays(MAX_CONSECUTIVE_DAYS)) ? weekStart : date.minusDays(MAX_CONSECUTIVE_DAYS);
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate to = weekEnd.isAfter(date.plusDays(MAX_CONSECUTIVE_DAYS)) ? weekEnd : date.plusDays(MAX_CONSECUTIVE_DAYS);

        List<WorkSlot> slots = new ArrayList<>(proposedWeek);
        for (Schedule schedule : scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, from, to)) {
            boolean replacedByProposal = proposalWeekStart != null
                    && !schedule.getWorkDate().isBefore(proposalWeekStart)
                    && !schedule.getWorkDate().isAfter(proposalWeekStart.plusDays(6));
            if (!replacedByProposal) {
                slots.add(WorkSlot.of(schedule));
            }
        }
        return slots.stream().collect(Collectors.groupingBy(slot -> slot.employee().getId()));
    }

    private List<Candidate> rank(Long storeId, LocalDate date, LocalTime start, LocalTime end, Long excludeEmployeeId,
                                 Map<Long, List<WorkSlot>> slotsByEmployee) {
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        Map<Long, Availability> availabilityByEmployee = availabilityRepository.findByEmployee_StoreId(storeId).stream()
                .filter(a -> a.getDayOfWeek() == date.getDayOfWeek())
                .collect(Collectors.toMap(a -> a.getEmployee().getId(), a -> a));
        int shiftHours = ScheduleAutomationService.shiftHours(start, end);
        Map<Long, DayOffType> dayOffByEmployee = dayOffRequestRepository
                .findByEmployee_StoreIdAndDateBetweenOrderByDateAsc(storeId, date, date).stream()
                .collect(Collectors.toMap(r -> r.getEmployee().getId(), DayOffRequest::getType));
        Set<Long> missingSkillIds = findMissingSkillIds(storeId, date, start, end, excludeEmployeeId, slotsByEmployee);

        List<Candidate> candidates = new ArrayList<>();
        for (Employee employee : employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED)) {
            if (employee.getId().equals(excludeEmployeeId)) {
                continue;
            }
            List<WorkSlot> own = slotsByEmployee.getOrDefault(employee.getId(), List.of());
            int weeklyHours = own.stream()
                    .filter(s -> !s.date().isBefore(weekStart) && !s.date().isAfter(weekEnd))
                    .mapToInt(s -> ScheduleAutomationService.shiftHours(s.start(), s.end()))
                    .sum();

            DayOffType dayOff = dayOffByEmployee.get(employee.getId());
            Optional<String> blocker = dayOff == DayOffType.MUST
                    ? Optional.of("필수 휴무 신청일")
                    : findBlocker(availabilityByEmployee.get(employee.getId()), own, date, start, end, weeklyHours + shiftHours);
            boolean onPreferredDayOff = dayOff == DayOffType.PREFER;
            List<String> coveredSkills = employee.getSkills().stream()
                    .filter(skill -> missingSkillIds.contains(skill.getId()))
                    .map(Skill::getName)
                    .sorted()
                    .toList();
            List<String> reasons = blocker.map(List::of).orElseGet(() -> {
                List<String> r = new ArrayList<>();
                if (!coveredSkills.isEmpty()) {
                    r.add("필요 스킬 보유: " + String.join(", ", coveredSkills));
                }
                if (onPreferredDayOff) {
                    r.add("희망 휴무 신청일");
                }
                r.add("가용 시간 " + availabilityByEmployee.get(employee.getId()).getStartTime()
                        + "~" + availabilityByEmployee.get(employee.getId()).getEndTime());
                r.add("이번 주 " + weeklyHours + "시간 근무 중");
                r.add("시급 " + NumberFormat.getInstance(Locale.KOREA).format(wage(employee)) + "원");
                return r;
            });
            candidates.add(new Candidate(employee, blocker.isEmpty(), onPreferredDayOff, weeklyHours, coveredSkills, reasons));
        }

        candidates.sort(Comparator.comparing((Candidate c) -> !c.eligible())
                .thenComparing(c -> c.coveredSkills().isEmpty())
                .thenComparing(Candidate::onPreferredDayOff)
                .thenComparingInt(Candidate::weeklyHours)
                .thenComparingInt(c -> wage(c.employee()))
                .thenComparing(c -> c.employee().getName()));
        return candidates;
    }

    public boolean isEligible(Long storeId, LocalDate date, LocalTime start, LocalTime end, Long employeeId, Long excludeEmployeeId) {
        return find(storeId, date, start, end, excludeEmployeeId).stream()
                .anyMatch(c -> c.employee().getId().equals(employeeId) && c.eligible());
    }

    // 같은 시간대 교대의 필요 스킬 중, 결원자를 빼면 그날 같은 근무자로 채워지지 않는 스킬
    private Set<Long> findMissingSkillIds(Long storeId, LocalDate date, LocalTime start, LocalTime end, Long excludeEmployeeId,
                                          Map<Long, List<WorkSlot>> slotsByEmployee) {
        List<Employee> coworkers = slotsByEmployee.values().stream()
                .flatMap(List::stream)
                .filter(s -> s.date().equals(date) && s.start().equals(start) && s.end().equals(end))
                .map(WorkSlot::employee)
                .filter(e -> !e.getId().equals(excludeEmployeeId))
                .toList();
        return requirementRepository.findByShift_Store_Id(storeId).stream()
                .filter(r -> r.getShift().getStartTime().equals(start) && r.getShift().getEndTime().equals(end))
                .filter(r -> r.getShift().getDayOfWeek() == null || r.getShift().getDayOfWeek() == date.getDayOfWeek())
                .filter(r -> coworkers.stream().filter(e -> hasSkill(e, r.getSkill().getId())).count() < r.getRequiredCount())
                .map(r -> r.getSkill().getId())
                .collect(Collectors.toSet());
    }

    private static boolean hasSkill(Employee employee, Long skillId) {
        return employee.getSkills().stream().anyMatch(skill -> skill.getId().equals(skillId));
    }

    private Optional<String> findBlocker(Availability availability, List<WorkSlot> own, LocalDate date,
                                         LocalTime start, LocalTime end, int weeklyHoursWithShift) {
        if (availability == null) {
            return Optional.of("해당 요일 가용 시간 미등록");
        }
        if (!ScheduleAutomationService.fitsAvailability(start, end, availability.getStartTime(), availability.getEndTime())) {
            return Optional.of("가용 시간(" + availability.getStartTime() + "~" + availability.getEndTime() + ") 밖의 근무");
        }
        Set<LocalDate> workDays = own.stream().map(WorkSlot::date).collect(Collectors.toSet());
        if (workDays.contains(date)) {
            return Optional.of("같은 날 다른 근무가 있음");
        }
        if (weeklyHoursWithShift > MAX_WEEKLY_HOURS) {
            return Optional.of("주 52시간 초과");
        }
        if (wouldExceedConsecutiveDays(workDays, date)) {
            return Optional.of("연속 5일 근무가 됨");
        }
        return Optional.empty();
    }

    private boolean wouldExceedConsecutiveDays(Set<LocalDate> workDays, LocalDate date) {
        for (int offset = -MAX_CONSECUTIVE_DAYS; offset <= 0; offset++) {
            LocalDate windowStart = date.plusDays(offset);
            boolean allWorked = true;
            for (int i = 0; i <= MAX_CONSECUTIVE_DAYS; i++) {
                LocalDate day = windowStart.plusDays(i);
                if (!day.equals(date) && !workDays.contains(day)) {
                    allWorked = false;
                    break;
                }
            }
            if (allWorked) {
                return true;
            }
        }
        return false;
    }

    private static int wage(Employee employee) {
        return employee.getHourlyWage() != null ? employee.getHourlyWage() : 0;
    }
}
