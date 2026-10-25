package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Attendance;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Notice;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.OfferStatus;
import com.example.store_scheduler_backend.domain.SwapOffer;
import com.example.store_scheduler_backend.repository.AttendanceRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.PreferenceSubmissionRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.SwapOfferRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// 점주 홈·알바 홈 화면에 필요한 요약을 한 번에 모은다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HomeService {

    private static final double NIGHT_BONUS_RATE = 0.5;

    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final ScheduleRepository scheduleRepository;
    private final ShiftRepository shiftRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final SwapOfferRepository offerRepository;
    private final AttendanceRepository attendanceRepository;
    private final PayrollService payrollService;
    private final PreferenceSubmissionRepository submissionRepository;
    private final LeaveService leaveService;
    private final NoticeService noticeService;

    // 다음 달 희망 제출 현황. 점주 홈은 제출 인원, 알바 홈은 본인 제출 여부
    public record PreferenceStatus(YearMonth month, LocalDate deadline, long submittedCount, long employeeCount,
                                   boolean submittedByMe) {
    }

    public record ShiftStatus(String shiftName, LocalTime startTime, LocalTime endTime, int requiredStaff,
                              List<String> employeeNames) {
        public int shortage() {
            return Math.max(0, requiredStaff - employeeNames.size());
        }
    }

    public record OwnerHome(Store store, LocalDate date, LocalDate weekStart, long weeklyLaborCost, long employeeCount,
                            long pendingApprovals, List<SwapRequest> activeVacancies, List<ShiftStatus> todayShifts,
                            List<Attendance> todayAttendance, PreferenceStatus nextMonthPreferences, long pendingLeaves) {
    }

    public record DaySchedule(LocalDate date, Schedule schedule, String shiftName) {
    }

    public record WorkerHome(Store store, Employee employee, LocalDate date, DaySchedule today, List<String> coworkers,
                             Attendance todayAttendance, List<DaySchedule> week, List<SwapOffer> swapOffers,
                             PayrollService.EmployeePayroll monthlyPay, PreferenceStatus nextMonthPreferences,
                             LeaveService.Balance leaveBalance, Notice latestNotice) {
    }

    public OwnerHome getOwnerHome(Long storeId, LocalDate date, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Shift> shifts = sortedShifts(storeId);

        long weeklyLaborCost = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, weekStart, weekStart.plusDays(6))
                .stream()
                .mapToLong(this::laborCost)
                .sum();
        List<Schedule> today = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, date, date);
        List<ShiftStatus> todayShifts = shifts.stream()
                .filter(s -> s.getDayOfWeek() == null || s.getDayOfWeek() == date.getDayOfWeek())
                .map(s -> new ShiftStatus(s.getName(), s.getStartTime(), s.getEndTime(), s.getRequiredStaff(),
                        today.stream()
                                .filter(sc -> sc.getStartTime().equals(s.getStartTime()) && sc.getEndTime().equals(s.getEndTime()))
                                .map(sc -> sc.getEmployee().getDisplayName())
                                .sorted()
                                .toList()))
                .toList();
        List<SwapRequest> activeVacancies = swapRequestRepository.findByStoreIdOrderByCreatedAtDesc(storeId).stream()
                .filter(SwapRequest::isActive)
                .filter(r -> !r.getWorkDate().isBefore(date))
                .sorted(Comparator.comparing(SwapRequest::getWorkDate).thenComparing(SwapRequest::getStartTime))
                .toList();

        long employeeCount = employeeRepository.countByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        return new OwnerHome(store, date, weekStart, weeklyLaborCost, employeeCount,
                employeeRepository.countByStoreIdAndStatus(storeId, EmployeeStatus.PENDING),
                activeVacancies, todayShifts,
                attendanceRepository.findByStoreIdAndWorkDateOrderByClockInAsc(storeId, date),
                preferenceStatus(store, date, employeeCount, null),
                leaveService.countPending(storeId));
    }

    public WorkerHome getWorkerHome(Long storeId, LocalDate date, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        List<Shift> shifts = sortedShifts(storeId);
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<Schedule> own = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                employee.getId(), weekStart, weekStart.plusDays(6));
        List<DaySchedule> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            Optional<Schedule> schedule = own.stream().filter(s -> s.getWorkDate().equals(day)).findFirst();
            week.add(new DaySchedule(day, schedule.orElse(null), schedule.map(s -> shiftName(shifts, s)).orElse(null)));
        }
        DaySchedule today = week.get(date.getDayOfWeek().getValue() - 1);

        List<String> coworkers = today.schedule() == null ? List.of()
                : scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, date, date).stream()
                        .filter(s -> !s.getEmployee().getId().equals(employee.getId()))
                        .filter(s -> s.getStartTime().equals(today.schedule().getStartTime())
                                && s.getEndTime().equals(today.schedule().getEndTime()))
                        .map(s -> s.getEmployee().getDisplayName())
                        .sorted()
                        .toList();
        List<SwapOffer> swapOffers = offerRepository.findByCandidateIdAndStatusOrderBySentAtDesc(employee.getId(), OfferStatus.SENT)
                .stream()
                .filter(o -> o.getSwapRequest().isActive())
                .toList();

        return new WorkerHome(employee.getStore(), employee, date, today, coworkers,
                attendanceRepository.findByEmployeeIdAndWorkDate(employee.getId(), date).orElse(null),
                week, swapOffers, payrollService.calculateForEmployee(employee, YearMonth.from(date)),
                preferenceStatus(employee.getStore(), date,
                        employeeRepository.countByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED), employee),
                leaveService.balanceOf(employee), noticeService.findLatest(storeId).orElse(null));
    }

    private PreferenceStatus preferenceStatus(Store store, LocalDate date, long employeeCount, Employee me) {
        YearMonth next = YearMonth.from(date).plusMonths(1);
        List<Long> submitted = submissionRepository.findByEmployee_StoreIdAndMonth(store.getId(), next.atDay(1)).stream()
                .map(s -> s.getEmployee().getId())
                .toList();
        return new PreferenceStatus(next, PreferenceService.deadlineOf(store, next), submitted.size(), employeeCount,
                me != null && submitted.contains(me.getId()));
    }

    // 확정 근무표의 예상 인건비. 후보안 비교와 같은 기준으로 야간 교대는 50% 가산
    private long laborCost(Schedule schedule) {
        int wage = schedule.getEmployee().getHourlyWage() != null ? schedule.getEmployee().getHourlyWage() : 0;
        long minutes = Duration.between(schedule.getStartTime(), schedule.getEndTime()).toMinutes();
        if (minutes <= 0) {
            minutes += 24 * 60;
        }
        long base = Math.round(minutes / 60.0 * wage);
        return ScheduleAutomationService.isNightShift(schedule.getStartTime(), schedule.getEndTime())
                ? Math.round(base * (1 + NIGHT_BONUS_RATE))
                : base;
    }

    // 근무표는 교대를 참조하지 않고 시간만 가지고 있어서, 같은 시간대 교대 이름을 찾아 붙인다
    static String shiftName(List<Shift> shifts, Schedule schedule) {
        return shifts.stream()
                .filter(s -> s.getStartTime().equals(schedule.getStartTime()) && s.getEndTime().equals(schedule.getEndTime()))
                .map(Shift::getName)
                .findFirst()
                .orElse(null);
    }

    private List<Shift> sortedShifts(Long storeId) {
        return shiftRepository.findByStoreId(storeId).stream()
                .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId))
                .toList();
    }
}
