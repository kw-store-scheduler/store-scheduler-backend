package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.LeaveRequest;
import com.example.store_scheduler_backend.domain.LeaveStatus;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.LeaveRequestRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

// 정규직 연차. 근로기준법 60조 기준으로 근무 시작일에서 발생 일수를 계산한다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LeaveService {

    private static final List<LeaveStatus> COUNTED = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);

    private final StoreAccessGuard storeAccessGuard;
    private final LeaveRequestRepository leaveRequestRepository;
    private final DayOffRequestRepository dayOffRequestRepository;
    private final ScheduleRepository scheduleRepository;
    private final FcmService fcmService;

    // used에는 승인 대기 중인 신청도 포함한다 (중복 신청으로 남은 일수를 넘지 않도록)
    public record Balance(LocalDate leaveYearStart, int entitled, int used) {
        public int remaining() {
            return Math.max(0, entitled - used);
        }
    }

    public record Mine(Balance balance, List<LeaveRequest> requests) {
    }

    // 1년 미만: 1개월 개근마다 1일(최대 11일). 1년 이상: 15일 + 3년차부터 2년마다 1일(최대 25일). 근무 시작일이 없으면 0일
    static int entitledDays(LocalDate startDate, LocalDate today) {
        if (startDate == null || today.isBefore(startDate)) {
            return 0;
        }
        long years = ChronoUnit.YEARS.between(startDate, today);
        if (years < 1) {
            return (int) Math.min(11, ChronoUnit.MONTHS.between(startDate, today));
        }
        return (int) Math.min(25, 15 + (years - 1) / 2);
    }

    static LocalDate leaveYearStart(LocalDate startDate, LocalDate today) {
        if (startDate == null) {
            return today;
        }
        long years = ChronoUnit.YEARS.between(startDate, today);
        return years < 1 ? startDate : startDate.plusYears(years);
    }

    // 정규직이 아니면 null
    public Balance balanceOf(Employee employee) {
        if (employee.getEmploymentType() != EmploymentType.FULL_TIME) {
            return null;
        }
        LocalDate today = LocalDate.now();
        LocalDate yearStart = leaveYearStart(employee.getStartDate(), today);
        return new Balance(yearStart, entitledDays(employee.getStartDate(), today),
                (int) leaveRequestRepository.countByEmployeeIdAndStatusInAndDateGreaterThanEqual(employee.getId(), COUNTED, yearStart));
    }

    public Mine findMine(Long storeId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        return new Mine(balanceOf(me), leaveRequestRepository.findByEmployeeIdOrderByDateDesc(me.getId()));
    }

    @Transactional
    public LeaveRequest request(Long storeId, LocalDate date, String reason, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        Balance balance = balanceOf(me);
        if (balance == null) {
            throw new IllegalArgumentException("연차는 정규직만 신청할 수 있습니다. 알바는 휴무 신청을 이용해주세요.");
        }
        if (date.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("지난 날짜에는 연차를 신청할 수 없습니다.");
        }
        if (leaveRequestRepository.existsByEmployeeIdAndDateAndStatusIn(me.getId(), date, COUNTED)) {
            throw new IllegalArgumentException("이미 연차를 신청한 날짜입니다.");
        }
        if (balance.remaining() < 1) {
            throw new IllegalArgumentException("남은 연차가 없습니다.");
        }
        LeaveRequest saved = leaveRequestRepository.save(new LeaveRequest(me, date, reason));
        fcmService.sendToDevice(me.getStore().getOwner().getDeviceToken(), "연차 신청",
                me.getDisplayName() + "님이 " + date + " 연차를 신청했습니다.");
        return saved;
    }

    public List<LeaveRequest> findPending(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return leaveRequestRepository.findByEmployee_StoreIdAndStatusOrderByDateAsc(storeId, LeaveStatus.PENDING);
    }

    public long countPending(Long storeId) {
        return leaveRequestRepository.countByEmployee_StoreIdAndStatus(storeId, LeaveStatus.PENDING);
    }

    // 승인하면 그날 휴무 신청을 필수 휴무로 바꿔 다음 근무표 생성부터 지켜지게 한다. 이미 확정된 근무가 있으면 hasSchedule로 알려준다
    @Transactional
    public LeaveRequest approve(Long storeId, Long leaveId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        LeaveRequest leave = findPendingLeave(storeId, leaveId);
        leave.approve();
        Employee employee = leave.getEmployee();
        dayOffRequestRepository.findByEmployeeIdAndDate(employee.getId(), leave.getDate())
                .ifPresent(r -> {
                    dayOffRequestRepository.delete(r);
                    dayOffRequestRepository.flush();
                });
        dayOffRequestRepository.save(new DayOffRequest(employee, leave.getDate(), DayOffType.MUST, "연차"));
        fcmService.sendToDevice(employee.getUser().getDeviceToken(), "연차 승인", leave.getDate() + " 연차가 승인되었습니다.");
        return leave;
    }

    @Transactional
    public LeaveRequest reject(Long storeId, Long leaveId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        LeaveRequest leave = findPendingLeave(storeId, leaveId);
        leave.reject();
        fcmService.sendToDevice(leave.getEmployee().getUser().getDeviceToken(), "연차 반려", leave.getDate() + " 연차가 반려되었습니다.");
        return leave;
    }

    public boolean hasSchedule(LeaveRequest leave) {
        return !scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                leave.getEmployee().getId(), leave.getDate(), leave.getDate()).isEmpty();
    }

    private LeaveRequest findPendingLeave(Long storeId, Long leaveId) {
        return leaveRequestRepository.findById(leaveId)
                .filter(l -> l.getEmployee().getStore().getId().equals(storeId) && l.getStatus() == LeaveStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("처리할 수 있는 연차 신청이 아닙니다."));
    }
}
