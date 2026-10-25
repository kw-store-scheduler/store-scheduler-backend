package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.SwapStatus;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SwapRequestService {

    private final SwapRequestRepository swapRequestRepository;
    private final ScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;
    private final OpenShiftService openShiftService;

    // 직원: 본인 근무에 대한 결원 요청
    @Transactional
    public SwapRequest request(Long storeId, Long scheduleId, String reason, String username) {
        Employee requester = findApprovedEmployee(storeId, username);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무입니다."));
        if (!schedule.getEmployee().getId().equals(requester.getId())) {
            throw new AccessDeniedException("본인 근무에 대해서만 결원 요청을 할 수 있습니다.");
        }
        if (schedule.getWorkDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("이미 지난 근무입니다.");
        }
        boolean alreadyRequested = swapRequestRepository.findBySchedule_IdIn(List.of(scheduleId)).stream()
                .anyMatch(SwapRequest::isActive);
        if (alreadyRequested) {
            throw new IllegalArgumentException("이미 결원 요청이 진행 중인 근무입니다.");
        }

        SwapRequest saved = swapRequestRepository.save(new SwapRequest(schedule, reason));
        Store store = schedule.getStore();
        fcmService.sendToDevice(store.getOwner().getDeviceToken(), "결원 요청",
                requester.getName() + "님이 " + schedule.getWorkDate() + " " + schedule.getStartTime() + " 근무에 결원을 요청했습니다.");
        // 신고와 동시에 빈 근무 게시판에 올리고 대신할 수 있는 직원들에게 알린다
        openShiftService.postVacancy(saved);
        return saved;
    }

    // 관리자: 매장 결원 요청 목록
    public List<SwapRequest> findByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return swapRequestRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
    }

    // 관리자: 대타 추천 (가능한 직원 우선, 불가능한 직원은 사유 포함)
    public List<SubstituteFinder.Candidate> findCandidates(Long storeId, Long requestId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findRequest(storeId, requestId);
        return substituteFinder.find(storeId, request.getWorkDate(), request.getStartTime(), request.getEndTime(),
                request.getRequester().getId());
    }

    // 관리자: 후보별 연락 상태. 지금 지정된 대타는 OFFERED, 게시판에 지원한 직원은 지원 상태(APPLIED 등)
    public Map<Long, String> findCandidateContacts(Long storeId, Long requestId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findRequest(storeId, requestId);
        Map<Long, String> contacts = new HashMap<>();
        openShiftService.applicationStatusesFor(requestId).forEach((employeeId, status) -> contacts.put(employeeId, status.name()));
        if (request.getStatus() == SwapStatus.OFFERED) {
            contacts.put(request.getSubstitute().getId(), SwapStatus.OFFERED.name());
        }
        return contacts;
    }

    // 관리자: 대타 지정 → 대상 직원에게 알림
    @Transactional
    public SwapRequest offer(Long storeId, Long requestId, Long substituteId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findActiveRequest(storeId, requestId);
        if (!isEligible(request, substituteId)) {
            throw new IllegalArgumentException("해당 직원은 이 근무를 대신할 수 없습니다. 대타 추천 목록을 확인해주세요.");
        }
        Employee substitute = employeeRepository.findById(substituteId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 직원입니다."));
        request.offer(substitute);
        fcmService.sendToDevice(substitute.getUser().getDeviceToken(), "대타 요청",
                request.getWorkDate() + " " + request.getStartTime() + " 근무 대타 요청이 도착했습니다.");
        return request;
    }

    // 대타 직원: 수락하면 근무 담당자가 바뀐다
    @Transactional
    public SwapRequest accept(Long storeId, Long requestId, String username) {
        SwapRequest request = findOfferedTo(storeId, requestId, username);
        // 지정 이후 다른 근무가 잡혔을 수 있으므로 수락 시점에 다시 확인
        if (!isEligible(request, request.getSubstitute().getId())) {
            throw new IllegalArgumentException("현재 근무 상황으로는 이 근무를 대신할 수 없습니다.");
        }
        request.accept();
        openShiftService.closeFor(List.of(request.getId()), true);
        String message = request.getWorkDate() + " " + request.getStartTime() + " 근무를 "
                + request.getSubstitute().getName() + "님이 대신하기로 했습니다.";
        fcmService.sendToDevice(request.getRequester().getUser().getDeviceToken(), "대타 확정", message);
        fcmService.sendToDevice(request.getStore().getOwner().getDeviceToken(), "대타 확정", message);
        return request;
    }

    // 대타 직원: 거절하면 다시 대타를 찾는 상태로 돌아간다
    @Transactional
    public SwapRequest decline(Long storeId, Long requestId, String username) {
        SwapRequest request = findOfferedTo(storeId, requestId, username);
        String substituteName = request.getSubstitute().getName();
        request.decline();
        fcmService.sendToDevice(request.getStore().getOwner().getDeviceToken(), "대타 거절",
                substituteName + "님이 " + request.getWorkDate() + " 근무 대타를 거절했습니다.");
        return request;
    }

    // 요청 직원: 대타가 확정되기 전이면 취소 가능
    @Transactional
    public SwapRequest cancel(Long storeId, Long requestId, String username) {
        Employee employee = findApprovedEmployee(storeId, username);
        SwapRequest request = findActiveRequest(storeId, requestId);
        if (!request.getRequester().getId().equals(employee.getId())) {
            throw new AccessDeniedException("본인이 요청한 결원만 취소할 수 있습니다.");
        }
        request.cancel();
        openShiftService.closeFor(List.of(request.getId()), false);
        return request;
    }

    // 근무가 삭제·교체되기 전에 호출해 진행 중인 요청을 정리한다
    @Transactional
    public void detachSchedules(Collection<Schedule> schedules) {
        if (schedules.isEmpty()) {
            return;
        }
        List<Long> ids = schedules.stream().map(Schedule::getId).toList();
        List<SwapRequest> requests = swapRequestRepository.findBySchedule_IdIn(ids);
        openShiftService.closeFor(requests.stream().filter(SwapRequest::isActive).map(SwapRequest::getId).toList(), false);
        requests.forEach(SwapRequest::detachSchedule);
    }

    private boolean isEligible(SwapRequest request, Long employeeId) {
        return substituteFinder.isEligible(request.getStore().getId(), request.getWorkDate(),
                request.getStartTime(), request.getEndTime(), employeeId, request.getRequester().getId());
    }

    private SwapRequest findOfferedTo(Long storeId, Long requestId, String username) {
        Employee employee = findApprovedEmployee(storeId, username);
        SwapRequest request = findRequest(storeId, requestId);
        if (request.getStatus() != SwapStatus.OFFERED || !request.getSubstitute().getId().equals(employee.getId())) {
            throw new AccessDeniedException("본인에게 온 대타 요청만 처리할 수 있습니다.");
        }
        return request;
    }

    private SwapRequest findActiveRequest(Long storeId, Long requestId) {
        SwapRequest request = findRequest(storeId, requestId);
        if (!request.isActive()) {
            throw new IllegalArgumentException("이미 처리된 결원 요청입니다.");
        }
        return request;
    }

    private SwapRequest findRequest(Long storeId, Long requestId) {
        SwapRequest request = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 결원 요청입니다."));
        if (!request.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 결원 요청이 아닙니다.");
        }
        return request;
    }

    private Employee findApprovedEmployee(Long storeId, String username) {
        return employeeRepository.findByStoreIdAndUser_Username(storeId, username)
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
                .orElseThrow(() -> new AccessDeniedException("해당 매장의 승인된 직원만 이용할 수 있습니다."));
    }
}
