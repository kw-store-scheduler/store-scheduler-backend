package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.PayDeduction;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.domain.WageChange;
import com.example.store_scheduler_backend.repository.EmployeeChangeLogRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.EmployeeSkillLevelRepository;
import com.example.store_scheduler_backend.repository.FixedScheduleRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.repository.WageChangeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final FcmService fcmService;
    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeProfileService profileService;
    private final ScheduleRepository scheduleRepository;
    private final SwapRequestService swapRequestService;
    private final ShiftTradeService shiftTradeService;
    private final OpenShiftService openShiftService;
    private final WageChangeRepository wageChangeRepository;
    private final FixedScheduleRepository fixedScheduleRepository;
    private final EmployeeSkillLevelRepository skillLevelRepository;
    private final EmployeeChangeLogRepository changeLogRepository;

    // removedShifts: 마지막 근무일 뒤에 잡혀 있어 지운 근무 수. 비게 된 자리는 점주가 채운다
    public record Resignation(LocalDate lastWorkDate, int removedShifts) {
    }

    @Transactional
    public Employee joinStore(String storeCode, String name, String phoneNumber, String username) {
        return joinStore(storeCode, name, phoneNumber, null, null, false, null, username);
    }

    @Transactional
    public Employee joinStore(String storeCode, String name, String phoneNumber, String nickname, LocalDate startDate,
                              boolean minor, EmploymentType employmentType, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        Store store = storeRepository.findByStoreCode(storeCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 매장 코드입니다."));

        Optional<Employee> existing = employeeRepository.findByStoreIdAndUserId(store.getId(), user.getId());
        if (existing.isPresent() && (existing.get().getStatus() == EmployeeStatus.PENDING
                || existing.get().getStatus() == EmployeeStatus.APPROVED)) {
            throw new IllegalArgumentException("이미 해당 매장에 신청한 이력이 있습니다.");
        }

        Employee employee = existing.orElseGet(() -> new Employee(name, phoneNumber, store, user));
        employee.reapply(name, phoneNumber);
        employee.fillJoinProfile(nickname, startDate, minor, employmentType);
        Employee saved = employeeRepository.save(employee);
        fcmService.send(store.getOwner(), "가입 요청",
                employee.getName() + "님이 " + store.getName() + " 가입을 요청했습니다.");
        return saved;
    }

    // 대기 중인 본인 신청만 취소한다. 일한 적 있는 직원의 재합류 신청이면 기록을 남기고 퇴사 상태로 되돌린다
    @Transactional
    public void cancelJoin(Long storeId, String username) {
        Employee employee = employeeRepository.findByStoreIdAndUser_Username(storeId, username)
                .filter(e -> e.getStatus() == EmployeeStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("취소할 합류 신청이 없습니다."));
        if (employee.getResignedOn() != null) {
            employee.cancelReapply();
        } else {
            fixedScheduleRepository.deleteAll(fixedScheduleRepository.findByEmployeeId(employee.getId()));
            skillLevelRepository.deleteAll(skillLevelRepository.findByEmployeeId(employee.getId()));
            changeLogRepository.deleteAll(changeLogRepository.findByEmployeeIdOrderByCreatedAtDescIdDesc(employee.getId()));
            wageChangeRepository.deleteAll(wageChangeRepository.findByEmployeeIdOrderByIdAsc(employee.getId()));
            employeeRepository.delete(employee);
        }
        fcmService.send(employee.getStore().getOwner(), "가입 신청 취소", employee.getName() + "님이 가입 신청을 취소했습니다.");
    }

    @Transactional
    public void approve(Long storeId, Long employeeId, int hourlyWage, EmploymentType employmentType, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청만 처리할 수 있습니다.");
        }
        // 재합류하며 시급이 바뀌면 이전 근무의 급여가 달라지지 않게 남긴다
        recordWageChange(employee, hourlyWage);
        employee.approve(hourlyWage, employmentType);

        fcmService.send(employee.getUser(),
                "가입 신청 승인",
                store.getName() + "에서 가입 신청이 승인되었습니다."
        );
    }

    @Transactional
    public void reject(Long storeId, Long employeeId, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 신청만 처리할 수 있습니다.");
        }
        employee.reject();

        fcmService.send(employee.getUser(),
                "가입 신청 거절",
                store.getName() + "에서 가입 신청이 거절되었습니다."
        );
    }

    // 지난 근무와 급여 기록은 남기고 마지막 근무일 이후 근무만 지운다. 미래 날짜면 그날까지 앱을 쓰도록 예약만 하고, 지나면 작업이 마무리한다
    @Transactional
    public Resignation resign(Long storeId, Long employeeId, LocalDate lastWorkDate, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (employee.getStatus() != EmployeeStatus.APPROVED) {
            throw new IllegalArgumentException("승인된 직원만 퇴사 처리할 수 있습니다.");
        }
        LocalDate last = lastWorkDate != null ? lastWorkDate : LocalDate.now();
        List<Schedule> removed = scheduleRepository.findByStoreIdAndEmployeeIdOrderByWorkDateAscStartTimeAsc(storeId, employeeId).stream()
                .filter(s -> s.getWorkDate().isAfter(last))
                .toList();
        swapRequestService.detachSchedules(removed);
        shiftTradeService.detachSchedules(removed.stream().map(Schedule::getId).toList());
        scheduleRepository.deleteAll(removed);
        swapRequestService.releaseSubstitute(employee, last);
        shiftTradeService.cancelOpenOf(employee, last);
        openShiftService.withdrawAll(employee, last);
        if (last.isAfter(LocalDate.now())) {
            employee.scheduleResignation(last);
            profileService.recordChange(employee, store.getName() + " 퇴사 예정 (마지막 근무일 " + last + ")");
        } else {
            finishResignation(employee, last);
        }
        return new Resignation(last, removed.size());
    }

    @Transactional
    public void cancelScheduledResignation(Long storeId, Long employeeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId) || employee.getStatus() != EmployeeStatus.APPROVED
                || employee.getResignedOn() == null) {
            throw new IllegalArgumentException("퇴사 예정인 직원이 아닙니다.");
        }
        employee.cancelScheduledResignation();
        profileService.recordChange(employee, "퇴사 예정 취소");
    }

    // 예약한 마지막 근무일이 지난 직원을 퇴사 처리한다
    @Transactional
    public int finishDueResignations(LocalDate today) {
        List<Employee> due = employeeRepository.findByStatusAndResignedOnBefore(EmployeeStatus.APPROVED, today);
        due.forEach(employee -> finishResignation(employee, employee.getResignedOn()));
        return due.size();
    }

    private void finishResignation(Employee employee, LocalDate last) {
        // 예약 뒤에 받은 요청까지 모두 닫는다
        swapRequestService.releaseSubstitute(employee, LocalDate.MIN);
        shiftTradeService.cancelOpenOf(employee, LocalDate.MIN);
        openShiftService.withdrawAll(employee, LocalDate.MIN);
        employee.resign(last);
        profileService.recordChange(employee, employee.getStore().getName() + " 퇴사 처리 (마지막 근무일 " + last + ")");
    }

    public List<Employee> findResignedByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.RESIGNED);
    }

    public List<Employee> findApprovedByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
    }

    public List<Employee> findPendingByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.PENDING);
    }

    @Transactional
    public void updateEmployee(Long storeId, Long employeeId, String name, String phoneNumber, Integer hourlyWage,
                               EmploymentType employmentType, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        Integer beforeWage = employee.getHourlyWage();
        EmploymentType beforeType = employee.getEmploymentType();
        if (hourlyWage != null) {
            recordWageChange(employee, hourlyWage);
        }
        employee.updateInfo(name, phoneNumber, hourlyWage, employmentType);
        if (hourlyWage != null && !hourlyWage.equals(beforeWage)) {
            profileService.recordChange(employee, "시급 " + beforeWage + "원 → " + hourlyWage + "원");
        }
        if (employmentType != null && employmentType != beforeType) {
            profileService.recordChange(employee, "고용 형태 " + (employmentType == EmploymentType.FULL_TIME ? "직원" : "알바") + "(으)로 변경");
        }
    }

    // 수습 감액은 1년 이상 계약, 단순노무 아님, 근무 시작일과 수습 기간이 있을 때만, 최저임금의 90% 이상으로 정할 수 있다
    @Transactional
    public Employee updatePaySettings(Long storeId, Long employeeId, PayDeduction deduction, Integer probationWage,
                                      boolean contractOneYearOrMore, boolean simpleLabor, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = findOne(employeeId);
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        if (probationWage != null) {
            if (!contractOneYearOrMore || simpleLabor) {
                throw new IllegalArgumentException("수습 감액은 1년 이상 근로계약이고 단순노무 직종이 아닐 때만 적용할 수 있습니다.");
            }
            if (employee.getStartDate() == null || employee.getProbationUntil() == null) {
                throw new IllegalArgumentException("근무 시작일과 수습 기간을 먼저 입력해주세요.");
            }
            int floor = LaborPolicy.probationWageFloor(LocalDate.now().getYear());
            if (probationWage < floor) {
                throw new IllegalArgumentException("수습 시급은 최저임금의 90%인 " + floor + "원 이상이어야 합니다.");
            }
        }
        employee.updatePaySettings(deduction, probationWage);
        profileService.recordChange(employee, "급여 설정 변경");
        return employee;
    }

    private void recordWageChange(Employee employee, int newWage) {
        Integer current = employee.getHourlyWage();
        if (current != null && current > 0 && current != newWage) {
            wageChangeRepository.save(new WageChange(employee, LocalDate.now(), current));
        }
    }

    public Employee findOne(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 직원입니다."));
    }

}
