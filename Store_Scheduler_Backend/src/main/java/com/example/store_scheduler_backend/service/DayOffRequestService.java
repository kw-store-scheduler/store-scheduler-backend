package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.DayOffFallback;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DayOffRequestService {

    private final DayOffRequestRepository dayOffRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreAccessGuard storeAccessGuard;

    public static final int MAX_MUST_PER_MONTH = 2;

    @Transactional
    public DayOffRequest request(Long storeId, LocalDate date, DayOffType type, String reason, String username) {
        return request(storeId, date, type, reason, List.of(), username);
    }

    // 필수 휴무는 한 달에 2일까지. 넘으면 희망 휴무로 받는다
    @Transactional
    public DayOffRequest request(Long storeId, LocalDate date, DayOffType type, String reason,
                                 List<DayOffFallback> fallbacks, String username) {
        Employee employee = findApprovedEmployee(storeId, username);
        if (date.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("지난 날짜에는 휴무를 신청할 수 없습니다.");
        }
        if (dayOffRequestRepository.existsByEmployeeIdAndDate(employee.getId(), date)) {
            throw new IllegalArgumentException("이미 휴무를 신청한 날짜입니다.");
        }
        YearMonth month = YearMonth.from(date);
        DayOffType accepted = type == DayOffType.MUST && dayOffRequestRepository.countByEmployeeIdAndTypeAndDateBetween(
                employee.getId(), DayOffType.MUST, month.atDay(1), month.atEndOfMonth()) >= MAX_MUST_PER_MONTH
                ? DayOffType.PREFER
                : type;
        List<DayOffFallback> kept = accepted == DayOffType.PREFER ? fallbacks.stream().distinct().toList() : List.of();
        return dayOffRequestRepository.save(new DayOffRequest(employee, date, accepted, reason, kept));
    }

    public List<DayOffRequest> findMine(Long storeId, String username) {
        Employee employee = findApprovedEmployee(storeId, username);
        return dayOffRequestRepository.findByEmployeeIdOrderByDateAsc(employee.getId());
    }

    public List<DayOffRequest> findByStore(Long storeId, LocalDate from, LocalDate to, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        }
        return dayOffRequestRepository.findByEmployee_StoreIdAndDateBetweenOrderByDateAsc(storeId, from, to);
    }

    @Transactional
    public void cancel(Long storeId, Long requestId, String username) {
        Employee employee = findApprovedEmployee(storeId, username);
        DayOffRequest request = dayOffRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 휴무 신청입니다."));
        if (!request.getEmployee().getId().equals(employee.getId())) {
            throw new AccessDeniedException("본인의 휴무 신청만 취소할 수 있습니다.");
        }
        dayOffRequestRepository.delete(request);
    }

    private Employee findApprovedEmployee(Long storeId, String username) {
        return employeeRepository.findByStoreIdAndUser_Username(storeId, username)
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
                .orElseThrow(() -> new AccessDeniedException("해당 매장의 승인된 직원만 이용할 수 있습니다."));
    }
}
