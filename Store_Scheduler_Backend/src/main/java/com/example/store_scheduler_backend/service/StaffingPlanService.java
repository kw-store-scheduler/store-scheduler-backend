package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.ExceptionCategory;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftDayStaffing;
import com.example.store_scheduler_backend.domain.StaffingException;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.ShiftDayStaffingRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.StaffingExceptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 교대의 날짜별 최소·목표 인원. 우선순위: 예외일 > 요일별 인원 > 교대 기본값(목표 requiredStaff, 최소 1명)
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StaffingPlanService {

    private final StoreAccessGuard storeAccessGuard;
    private final ShiftRepository shiftRepository;
    private final ShiftDayStaffingRepository dayStaffingRepository;
    private final StaffingExceptionRepository exceptionRepository;

    public record Staffing(int min, int target) {
        static final Staffing CLOSED = new Staffing(0, 0);
    }

    public record DayStaffingInput(DayOfWeek dayOfWeek, int minStaff, int targetStaff) {
    }

    // 근무표 생성 기간 동안의 인원 계획
    public static class Plan {
        private final Map<Long, Map<DayOfWeek, ShiftDayStaffing>> byShiftAndDay;
        private final List<StaffingException> exceptions;

        Plan(List<ShiftDayStaffing> dayStaffing, List<StaffingException> exceptions) {
            this.byShiftAndDay = new HashMap<>();
            for (ShiftDayStaffing d : dayStaffing) {
                byShiftAndDay.computeIfAbsent(d.getShift().getId(), k -> new HashMap<>()).put(d.getDayOfWeek(), d);
            }
            // 교대를 지정한 예외가 매장 전체 예외보다 우선
            this.exceptions = exceptions.stream()
                    .sorted(Comparator.comparing((StaffingException e) -> e.getShift() == null))
                    .toList();
        }

        public Staffing of(Shift shift, LocalDate date) {
            if (shift.getDayOfWeek() != null && shift.getDayOfWeek() != date.getDayOfWeek()) {
                return Staffing.CLOSED;
            }
            Staffing usual = usual(shift, date);
            for (StaffingException e : exceptions) {
                if (e.covers(date) && e.appliesTo(shift) && !e.isTimeOnly()) {
                    if (e.getDeltaStaff() != null) {
                        return new Staffing(Math.max(0, usual.min() + e.getDeltaStaff()), Math.max(0, usual.target() + e.getDeltaStaff()));
                    }
                    return new Staffing(e.getMinStaff(), e.getTargetStaff());
                }
            }
            return usual;
        }

        private Staffing usual(Shift shift, LocalDate date) {
            ShiftDayStaffing day = byShiftAndDay.getOrDefault(shift.getId(), Map.of()).get(date.getDayOfWeek());
            if (day != null) {
                return new Staffing(day.getMinStaff(), day.getTargetStaff());
            }
            return new Staffing(Math.min(1, shift.getRequiredStaff()), shift.getRequiredStaff());
        }

        // 그날 교대 시간 변경이 있으면 [시작, 종료], 없으면 null
        public LocalTime[] timeOverride(Shift shift, LocalDate date) {
            return exceptions.stream()
                    .filter(e -> e.covers(date) && e.appliesTo(shift)
                            && (e.getStartTimeOverride() != null || e.getEndTimeOverride() != null))
                    .findFirst()
                    .map(e -> new LocalTime[]{
                            e.getStartTimeOverride() != null ? e.getStartTimeOverride() : shift.getStartTime(),
                            e.getEndTimeOverride() != null ? e.getEndTimeOverride() : shift.getEndTime()})
                    .orElse(null);
        }
    }

    // 기간 예외는 시작일이 조회 범위 앞이어도 걸치면 포함한다
    public Plan planFor(Long storeId, LocalDate from, LocalDate to) {
        return new Plan(dayStaffingRepository.findByShift_Store_Id(storeId),
                exceptionRepository.findByStoreIdAndDateLessThanEqualOrderByDateAsc(storeId, to).stream()
                        .filter(e -> !e.getLastDate().isBefore(from))
                        .toList());
    }

    public Map<Long, List<ShiftDayStaffing>> findDayStaffingByStore(Long storeId) {
        Map<Long, List<ShiftDayStaffing>> result = new HashMap<>();
        for (ShiftDayStaffing d : dayStaffingRepository.findByShift_Store_Id(storeId)) {
            result.computeIfAbsent(d.getShift().getId(), k -> new ArrayList<>()).add(d);
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(ShiftDayStaffing::getDayOfWeek)));
        return result;
    }

    // 요일별 인원 표를 통째로 교체한다. 빠진 요일은 교대 기본값을 쓴다
    @Transactional
    public List<ShiftDayStaffing> replaceDayStaffing(Long storeId, Long shiftId, List<DayStaffingInput> inputs,
                                                     String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Shift shift = findStoreShift(storeId, shiftId);
        List<ShiftDayStaffing> rows = new ArrayList<>();
        for (DayStaffingInput input : inputs) {
            validate(input.minStaff(), input.targetStaff());
            if (rows.stream().anyMatch(r -> r.getDayOfWeek() == input.dayOfWeek())) {
                throw new IllegalArgumentException("같은 요일이 중복되었습니다: " + input.dayOfWeek());
            }
            rows.add(new ShiftDayStaffing(shift, input.dayOfWeek(), input.minStaff(), input.targetStaff()));
        }
        dayStaffingRepository.deleteAll(dayStaffingRepository.findByShiftId(shiftId));
        dayStaffingRepository.flush();
        return dayStaffingRepository.saveAll(rows);
    }

    public List<StaffingException> findExceptions(Long storeId, LocalDate from, LocalDate to, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        }
        return exceptionRepository.findByStoreIdAndDateLessThanEqualOrderByDateAsc(storeId, to).stream()
                .filter(e -> !e.getLastDate().isBefore(from))
                .toList();
    }

    public record ExceptionInput(LocalDate date, LocalDate endDate, Long shiftId, Integer minStaff, Integer targetStaff,
                                 Integer deltaStaff, ExceptionCategory category, LocalTime startTimeOverride,
                                 LocalTime endTimeOverride, String note) {
    }

    // 기간·분류·평소 대비 +N명·교대 시간 변경을 받는 예외일 등록
    @Transactional
    public StaffingException addException(Long storeId, ExceptionInput input, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        if (input.date() == null || (input.endDate() != null && input.endDate().isBefore(input.date()))) {
            throw new IllegalArgumentException("예외일 날짜를 확인해주세요.");
        }
        boolean timeChange = input.startTimeOverride() != null || input.endTimeOverride() != null;
        if (input.deltaStaff() == null && input.minStaff() == null && !timeChange) {
            throw new IllegalArgumentException("인원(minStaff 또는 deltaStaff)이나 교대 시간 변경 중 하나는 입력해주세요.");
        }
        if (input.deltaStaff() == null && input.minStaff() != null) {
            validate(input.minStaff(), input.targetStaff() != null ? input.targetStaff() : input.minStaff());
        }
        Shift shift = input.shiftId() != null ? findStoreShift(storeId, input.shiftId()) : null;
        if (timeChange && shift == null) {
            throw new IllegalArgumentException("교대 시간을 바꾸려면 교대를 골라주세요.");
        }
        LocalDate last = input.endDate() != null ? input.endDate() : input.date();
        boolean duplicated = exceptionRepository.findByStoreIdAndDateLessThanEqualOrderByDateAsc(storeId, last).stream()
                .filter(e -> !e.getLastDate().isBefore(input.date()))
                .anyMatch(e -> shift == null ? e.getShift() == null
                        : e.getShift() != null && e.getShift().getId().equals(shift.getId()));
        if (duplicated) {
            throw new IllegalArgumentException("같은 날짜·교대의 예외일이 이미 있습니다.");
        }
        Integer target = input.deltaStaff() == null && input.minStaff() != null
                ? (input.targetStaff() != null ? input.targetStaff() : input.minStaff()) : null;
        return exceptionRepository.save(new StaffingException(store, input.date(), input.endDate(), shift,
                input.deltaStaff() == null ? input.minStaff() : null, target, input.deltaStaff(), input.category(),
                input.startTimeOverride(), input.endTimeOverride(), input.note()));
    }

    @Transactional
    public StaffingException addException(Long storeId, LocalDate date, Long shiftId, int minStaff, int targetStaff,
                                          String note, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        validate(minStaff, targetStaff);
        Shift shift = shiftId != null ? findStoreShift(storeId, shiftId) : null;
        boolean duplicated = exceptionRepository.findByStoreIdAndDateBetweenOrderByDateAsc(storeId, date, date).stream()
                .anyMatch(e -> shift == null ? e.getShift() == null
                        : e.getShift() != null && e.getShift().getId().equals(shift.getId()));
        if (duplicated) {
            throw new IllegalArgumentException("같은 날짜·교대의 예외일이 이미 있습니다.");
        }
        return exceptionRepository.save(new StaffingException(store, date, shift, minStaff, targetStaff, note));
    }

    @Transactional
    public void deleteException(Long storeId, Long exceptionId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        StaffingException exception = exceptionRepository.findById(exceptionId)
                .filter(e -> e.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 예외일입니다."));
        exceptionRepository.delete(exception);
    }

    private static void validate(int minStaff, int targetStaff) {
        if (minStaff < 0 || targetStaff < minStaff) {
            throw new IllegalArgumentException("인원은 0명 이상이고, 목표 인원은 최소 인원보다 적을 수 없습니다.");
        }
    }

    private Shift findStoreShift(Long storeId, Long shiftId) {
        return shiftRepository.findById(shiftId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무 시간대입니다."));
    }
}
