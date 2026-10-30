package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ShiftTrade;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftTradeRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SwapRequestService swapRequestService;
    private final ShiftTradeService shiftTradeService;
    private final SubstituteFinder substituteFinder;
    private final SwapRequestRepository swapRequestRepository;
    private final ShiftTradeRepository tradeRepository;
    private final FcmService fcmService;

    public List<Schedule> findByStore(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(storeId);
    }

    public boolean isOwner(Long storeId, String username) {
        return storeAccessGuard.getStore(storeId).getOwner().getUsername().equals(username);
    }

    public List<Schedule> findMySchedules(Long storeId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Employee employee = employeeRepository.findByStoreIdAndUserId(storeId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 직원이 아닙니다."));

        if (employee.getStatus() != EmployeeStatus.APPROVED) {
            throw new IllegalArgumentException("승인된 직원만 근무표를 조회할 수 있습니다.");
        }

        return scheduleRepository.findByStoreIdAndEmployeeIdOrderByWorkDateAscStartTimeAsc(storeId, employee.getId());
    }

    // 점주가 근무를 직접 추가한다. 근무 규칙에 걸리면 사유를 알려 주고, force면 점주 판단으로 진행한다
    @Transactional
    public Schedule create(Long storeId, Long employeeId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                           boolean force, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = findApproved(storeId, employeeId);
        requireValidTimes(startTime, endTime);
        requireStillEmployed(employee, workDate);
        requireAllowed(storeId, employee, workDate, startTime, endTime, List.of(), force);

        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setEmployee(employee);
        schedule.setWorkDate(workDate);
        schedule.setStartTime(startTime);
        schedule.setEndTime(endTime);
        Schedule saved = scheduleRepository.save(schedule);
        notifyIfUpcoming(employee, "근무 추가", saved);
        return saved;
    }

    // employeeId를 주면 담당자도 바꾼다. 대타·근무 바꾸기가 진행 중인 근무는 그 요청과 어긋나므로 먼저 끝내야 한다
    @Transactional
    public Schedule update(Long storeId, Long scheduleId, Long employeeId, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                           boolean force, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Schedule schedule = findSchedule(scheduleId, storeId);
        requireValidTimes(startTime, endTime);
        if (swapRequestRepository.findBySchedule_IdIn(List.of(scheduleId)).stream().anyMatch(SwapRequest::isActive)) {
            throw new IllegalArgumentException("결원 요청이 진행 중인 근무입니다. 요청을 먼저 처리해주세요.");
        }
        if (tradeRepository.findByRequesterSchedule_IdInOrTargetSchedule_IdIn(List.of(scheduleId), List.of(scheduleId)).stream()
                .anyMatch(ShiftTrade::isOpen)) {
            throw new IllegalArgumentException("근무 바꾸기 요청이 진행 중인 근무입니다. 요청을 먼저 처리해주세요.");
        }
        Employee before = schedule.getEmployee();
        Employee after = employeeId != null && !employeeId.equals(before.getId()) ? findApproved(storeId, employeeId) : before;
        requireStillEmployed(after, workDate);
        requireAllowed(storeId, after, workDate, startTime, endTime, List.of(schedule), force);

        LocalDate beforeDate = schedule.getWorkDate();
        schedule.setEmployee(after);
        schedule.setWorkDate(workDate);
        schedule.setStartTime(startTime);
        schedule.setEndTime(endTime);
        schedule.setAssignmentReasons(null);
        notifyIfUpcoming(after, after == before ? "근무 변경" : "근무 추가", schedule);
        if (after != before && !beforeDate.isBefore(LocalDate.now())) {
            fcmService.send(before.getUser(), "근무 변경", beforeDate + " 근무가 다른 직원으로 바뀌었습니다.");
        }
        return schedule;
    }

    @Transactional
    public void delete(Long storeId, Long scheduleId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Schedule schedule = findSchedule(scheduleId, storeId);
        swapRequestService.detachSchedules(List.of(schedule));
        shiftTradeService.detachSchedules(List.of(schedule.getId()));
        if (!schedule.getWorkDate().isBefore(LocalDate.now())) {
            fcmService.send(schedule.getEmployee().getUser(), "근무 취소", schedule.getWorkDate() + " " + schedule.getStartTime() + " 근무가 취소되었습니다.");
        }
        scheduleRepository.delete(schedule);
    }

    // 지난 날짜는 실제 근무 기록을 바로잡는 경우라 근무 규칙을 묻지 않는다
    private void requireAllowed(Long storeId, Employee employee, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                                List<Schedule> removed, boolean force) {
        if (force || workDate.isBefore(LocalDate.now())) {
            return;
        }
        substituteFinder.blockerAfterChanges(storeId, employee, workDate, startTime, endTime, removed, List.of())
                .ifPresent(blocker -> {
                    throw new IllegalArgumentException(employee.getDisplayName() + "님에게 배정할 수 없습니다: " + blocker
                            + ". 그래도 배정하려면 force로 요청해주세요.");
                });
    }

    private static void requireStillEmployed(Employee employee, LocalDate workDate) {
        if (employee.isGoneBy(workDate)) {
            throw new IllegalArgumentException(employee.getDisplayName() + "님의 마지막 근무일(" + employee.getResignedOn() + ") 뒤에는 배정할 수 없습니다.");
        }
    }

    private static void requireValidTimes(LocalTime startTime, LocalTime endTime) {
        if (startTime.equals(endTime)) {
            throw new IllegalArgumentException("시작 시간과 종료 시간이 같을 수 없습니다.");
        }
    }

    private Employee findApproved(Long storeId, Long employeeId) {
        return employeeRepository.findById(employeeId)
                .filter(e -> e.getStore().getId().equals(storeId) && e.getStatus() == EmployeeStatus.APPROVED)
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 승인된 직원이 아닙니다."));
    }

    private void notifyIfUpcoming(Employee employee, String title, Schedule schedule) {
        if (!schedule.getWorkDate().isBefore(LocalDate.now())) {
            fcmService.send(employee.getUser(), title,
                    schedule.getWorkDate() + " " + schedule.getStartTime() + "~" + schedule.getEndTime() + " 근무를 확인해주세요.");
        }
    }

    private Schedule findSchedule(Long scheduleId, Long storeId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스케줄입니다."));
        if (!schedule.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 스케줄이 아닙니다.");
        }
        return schedule;
    }
}
