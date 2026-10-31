package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.PayDeduction;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.service.EmployeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@Tag(name = "직원 API", description = "매장 합류 신청·승인 및 직원 관리")
@RequiredArgsConstructor
public class StoreEmployeeController {

    private final EmployeeService employeeService;

    // 직원: 매장 코드로 입점 신청
    @PostMapping("/api/stores/join/{storeCode}")
    public ResponseEntity<JoinResponse> joinStore(
            @PathVariable String storeCode,
            @Valid @RequestBody JoinRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Employee employee = employeeService.joinStore(storeCode, request.getName(), request.getPhoneNumber(),
                request.getNickname(), request.getStartDate(), Boolean.TRUE.equals(request.getMinor()),
                request.getEmploymentType(), userDetails.getUsername());
        return ResponseEntity.ok(new JoinResponse(employee.getId(), employee.getStatus().name()));
    }

    // 관리자: 승인 대기 목록 조회
    // 직원: 승인 전 합류 신청 취소
    @DeleteMapping("/api/stores/{storeId}/join")
    public ResponseEntity<Void> cancelJoin(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.cancelJoin(storeId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/stores/{storeId}/employees/pending")
    public ResponseEntity<List<EmployeeResponse>> getPendingEmployees(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<EmployeeResponse> result = employeeService.findPendingByStore(storeId, userDetails.getUsername()).stream()
                .map(EmployeeResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 승인된 직원 목록 조회
    @GetMapping("/api/stores/{storeId}/employees")
    public ResponseEntity<List<EmployeeResponse>> getApprovedEmployees(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<EmployeeResponse> result = employeeService.findApprovedByStore(storeId, userDetails.getUsername()).stream()
                .map(EmployeeResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 퇴사한 직원 목록
    @GetMapping("/api/stores/{storeId}/employees/resigned")
    public ResponseEntity<List<EmployeeResponse>> getResignedEmployees(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(employeeService.findResignedByStore(storeId, userDetails.getUsername()).stream()
                .map(EmployeeResponse::from)
                .toList());
    }

    @Operation(summary = "퇴사 처리", description = "lastWorkDate를 생략하면 오늘입니다. 미래 날짜면 퇴사 예정으로 두고 그날까지 앱을 쓸 수 있으며, 날짜가 지나면 자동으로 처리됩니다. 지난 근무와 급여 기록은 남고, 마지막 근무일 뒤의 근무는 지워지며(removedShifts), 그 뒤에 걸린 대타·근무 바꾸기·지원은 닫힙니다.")
    @PostMapping("/api/stores/{storeId}/employees/{employeeId}/resign")
    public ResponseEntity<EmployeeService.Resignation> resign(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @RequestBody(required = false) ResignRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(employeeService.resign(storeId, employeeId,
                request != null ? request.getLastWorkDate() : null, userDetails.getUsername()));
    }

    // 관리자: 퇴사 예정 취소 (지워진 근무는 되돌리지 않는다)
    @DeleteMapping("/api/stores/{storeId}/employees/{employeeId}/resign")
    public ResponseEntity<Void> cancelResignation(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.cancelScheduledResignation(storeId, employeeId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "수습 시급·공제 설정", description = "probationWage는 1년 이상 계약(contractOneYearOrMore)이고 단순노무(simpleLabor)가 아닐 때만, 최저임금의 90% 이상으로 정할 수 있고 수습 시작 3개월까지 적용됩니다. null이면 해제합니다. deduction은 예상 실수령액 계산용입니다.")
    @PutMapping("/api/stores/{storeId}/employees/{employeeId}/pay-settings")
    public ResponseEntity<EmployeeResponse> updatePaySettings(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @RequestBody PaySettingsRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(EmployeeResponse.from(employeeService.updatePaySettings(storeId, employeeId, request.getDeduction(),
                request.getProbationWage(), Boolean.TRUE.equals(request.getContractOneYearOrMore()),
                Boolean.TRUE.equals(request.getSimpleLabor()), userDetails.getUsername())));
    }

    // 관리자: 신청 승인
    @PostMapping("/api/stores/{storeId}/employees/{employeeId}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @Valid @RequestBody ApproveRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.approve(storeId, employeeId, request.getHourlyWage(), request.getEmploymentType(), userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    // 관리자: 직원 정보 수정 (이름, 전화번호, 시급, 고용 형태)
    @PatchMapping("/api/stores/{storeId}/employees/{employeeId}")
    public ResponseEntity<Void> updateEmployee(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @RequestBody UpdateEmployeeRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.updateEmployee(storeId, employeeId, request.getName(), request.getPhoneNumber(), request.getHourlyWage(),
                request.getEmploymentType(), userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    // 관리자: 신청 거절
    @PostMapping("/api/stores/{storeId}/employees/{employeeId}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.reject(storeId, employeeId, userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    @Data
    static class PaySettingsRequest {
        private PayDeduction deduction;
        private Integer probationWage;
        private Boolean contractOneYearOrMore;
        private Boolean simpleLabor;
    }

    @Data
    static class ResignRequest {
        private LocalDate lastWorkDate;
    }

    @Data
    static class UpdateEmployeeRequest {
        private String name;
        private String phoneNumber;
        private Integer hourlyWage;
        private EmploymentType employmentType;
    }

    @Data
    static class JoinRequest {
        @NotBlank(message = "이름을 입력해주세요.")
        private String name;
        private String phoneNumber;
        private String nickname;
        private LocalDate startDate;
        private Boolean minor;
        // 생략하면 알바(PART_TIME). 점주가 승인할 때 바꿀 수 있다
        private EmploymentType employmentType;
    }

    @Data
    @AllArgsConstructor
    static class JoinResponse {
        private Long employeeId;
        private String status;
    }

    @Data
    static class ApproveRequest {
        @NotNull(message = "시급을 입력해주세요.")
        @Min(value = 1, message = "시급은 0보다 커야 합니다.")
        private Integer hourlyWage;
        // 생략하면 알바(PART_TIME)
        private EmploymentType employmentType;
    }

    @Data
    @AllArgsConstructor
    static class EmployeeResponse {
        private Long id;
        private String name;
        private String nickname;
        private String phoneNumber;
        private Integer hourlyWage;
        private Integer probationWage;
        private PayDeduction deduction;
        private String status;
        private EmploymentType employmentType;
        private LocalDate startDate;
        // 퇴사 예정일 또는 퇴사일
        private LocalDate resignedOn;
        private boolean minor;
        private LocalDateTime requestedAt;
        private List<String> skills;

        static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(
                    e.getId(),
                    e.getName(),
                    e.getNickname(),
                    e.getPhoneNumber(),
                    e.getHourlyWage(),
                    e.getProbationWage(),
                    e.getDeduction(),
                    e.getStatus().name(),
                    e.getEmploymentType(),
                    e.getStartDate(),
                    e.getResignedOn(),
                    e.isMinor(),
                    e.getRequestedAt(),
                    e.getSkills().stream().map(Skill::getName).collect(Collectors.toList())
            );
        }
    }
}
