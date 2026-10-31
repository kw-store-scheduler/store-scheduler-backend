package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Attendance;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.repository.AttendanceRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AttendanceService {

    private static final Duration MAX_OPEN = Duration.ofHours(24);

    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;

    // scheduledStart가 없으면 근무표에 없는 날 출근한 것
    public record AttendanceView(Attendance attendance, LocalTime scheduledStart, LocalTime scheduledEnd) {
        public boolean late() {
            return scheduledStart != null && attendance.getClockIn().toLocalTime().isAfter(scheduledStart);
        }
    }

    public record Absence(Long employeeId, String employeeName, LocalTime scheduledStart, LocalTime scheduledEnd) {
    }

    @Transactional
    public AttendanceView clockIn(Long storeId, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        LocalDateTime now = LocalDateTime.now();
        if (attendanceRepository.findByEmployeeIdAndWorkDate(employee.getId(), now.toLocalDate()).isPresent()) {
            throw new IllegalArgumentException("오늘은 이미 출근 기록이 있습니다.");
        }
        return toView(attendanceRepository.save(new Attendance(employee, now)));
    }

    // 자정을 넘긴 근무는 전날 출근 기록에 퇴근을 찍는다
    @Transactional
    public AttendanceView clockOut(Long storeId, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        Attendance attendance = attendanceRepository.findFirstByEmployeeIdAndClockOutIsNullOrderByClockInDesc(employee.getId())
                .orElseThrow(() -> new IllegalArgumentException("퇴근할 출근 기록이 없습니다."));
        // 오래된 기록을 닫으면 며칠짜리 근무가 되므로, 퇴근을 빠뜨린 기록은 점주가 고치게 한다
        if (attendance.getClockIn().isBefore(LocalDateTime.now().minus(MAX_OPEN))) {
            throw new IllegalArgumentException("퇴근을 찍지 않은 " + attendance.getWorkDate() + " 출근 기록이 있습니다. 사장님께 수정을 요청해주세요.");
        }
        attendance.clockOut(LocalDateTime.now());
        return toView(attendance);
    }

    // 점주: 퇴근 누락·잘못 찍은 기록 수정
    @Transactional
    public AttendanceView correct(Long storeId, Long attendanceId, LocalDateTime clockIn, LocalDateTime clockOut, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Attendance attendance = attendanceRepository.findById(attendanceId)
                .filter(a -> a.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 출퇴근 기록입니다."));
        if (clockOut != null && !clockOut.isAfter(clockIn)) {
            throw new IllegalArgumentException("퇴근 시각은 출근 시각보다 늦어야 합니다.");
        }
        attendance.correct(clockIn, clockOut);
        return toView(attendance);
    }

    // 점주: 출근을 찍지 않은 날의 기록을 직접 만든다
    @Transactional
    public AttendanceView create(Long storeId, Long employeeId, LocalDateTime clockIn, LocalDateTime clockOut, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = employeeRepository.findById(employeeId)
                .filter(e -> e.getStore().getId().equals(storeId))
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED || e.getStatus() == EmployeeStatus.RESIGNED)
                .orElseThrow(() -> new IllegalArgumentException("해당 매장 소속 직원이 아닙니다."));
        LocalDate workDate = clockIn.toLocalDate();
        if (clockIn.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("앞으로의 출근 기록은 만들 수 없습니다.");
        }
        if (employee.isGoneBy(workDate)) {
            throw new IllegalArgumentException(employee.getDisplayName() + "님의 마지막 근무일(" + employee.getResignedOn() + ") 뒤의 기록은 만들 수 없습니다.");
        }
        if (clockOut != null && !clockOut.isAfter(clockIn)) {
            throw new IllegalArgumentException("퇴근 시각은 출근 시각보다 늦어야 합니다.");
        }
        if (attendanceRepository.findByEmployeeIdAndWorkDate(employeeId, workDate).isPresent()) {
            throw new IllegalArgumentException("이미 그날 출근 기록이 있습니다. 기존 기록을 수정해주세요.");
        }
        Attendance attendance = new Attendance(employee, clockIn);
        attendance.correct(clockIn, clockOut);
        return toView(attendanceRepository.save(attendance));
    }

    // 근무 시작 시각이 지났는데 그날 출근 기록이 없는 직원. 하루에 근무가 여럿이면 가장 이른 근무로 보여 준다
    public List<Absence> findAbsences(Long storeId, LocalDate date, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        LocalDateTime now = LocalDateTime.now();
        Set<Long> attended = attendanceRepository.findByStoreIdAndWorkDateOrderByClockInAsc(storeId, date).stream()
                .map(a -> a.getEmployee().getId())
                .collect(Collectors.toSet());
        Map<Long, Schedule> firstByEmployee = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, date, date).stream()
                .collect(Collectors.toMap(s -> s.getEmployee().getId(), Function.identity(),
                        BinaryOperator.minBy(Comparator.comparing(Schedule::getStartTime))));
        return firstByEmployee.values().stream()
                .filter(s -> !attended.contains(s.getEmployee().getId()))
                .filter(s -> !date.atTime(s.getStartTime()).isAfter(now))
                .sorted(Comparator.comparing(Schedule::getStartTime))
                .map(s -> new Absence(s.getEmployee().getId(), s.getEmployee().getDisplayName(), s.getStartTime(), s.getEndTime()))
                .toList();
    }

    public List<AttendanceView> findByDate(Long storeId, LocalDate date, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Map<Long, Schedule> scheduleByEmployee = scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, date, date).stream()
                .collect(Collectors.toMap(s -> s.getEmployee().getId(), Function.identity(), (a, b) -> a));
        return attendanceRepository.findByStoreIdAndWorkDateOrderByClockInAsc(storeId, date).stream()
                .map(a -> toView(a, Optional.ofNullable(scheduleByEmployee.get(a.getEmployee().getId()))))
                .toList();
    }

    public List<AttendanceView> findMine(Long storeId, LocalDate from, LocalDate to, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        Map<LocalDate, Schedule> scheduleByDate = scheduleRepository
                .findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employee.getId(), from, to).stream()
                .collect(Collectors.toMap(Schedule::getWorkDate, Function.identity(), (a, b) -> a));
        return attendanceRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employee.getId(), from, to).stream()
                .map(a -> toView(a, Optional.ofNullable(scheduleByDate.get(a.getWorkDate()))))
                .toList();
    }

    public Optional<Attendance> findToday(Long employeeId) {
        return attendanceRepository.findByEmployeeIdAndWorkDate(employeeId, LocalDate.now());
    }

    private AttendanceView toView(Attendance attendance) {
        Optional<Schedule> schedule = scheduleRepository
                .findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(attendance.getEmployee().getId(),
                        attendance.getWorkDate(), attendance.getWorkDate()).stream()
                .min(Comparator.comparing(Schedule::getStartTime));
        return toView(attendance, schedule);
    }

    private AttendanceView toView(Attendance attendance, Optional<Schedule> schedule) {
        return new AttendanceView(attendance,
                schedule.map(Schedule::getStartTime).orElse(null),
                schedule.map(Schedule::getEndTime).orElse(null));
    }
}
