package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.service.EmployeeService;
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

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class StoreEmployeeController {

    private final EmployeeService employeeService;

    // 직원: 매장 코드로 입점 신청
    @PostMapping("/api/stores/join/{storeCode}")
    public ResponseEntity<JoinResponse> joinStore(
            @PathVariable String storeCode,
            @Valid @RequestBody JoinRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Employee employee = employeeService.joinStore(storeCode, request.getName(), request.getPhoneNumber(), userDetails.getUsername());
        return ResponseEntity.ok(new JoinResponse(employee.getId(), employee.getStatus().name()));
    }

    // 관리자: 승인 대기 목록 조회
    @GetMapping("/api/stores/{storeId}/employees/pending")
    public ResponseEntity<List<EmployeeResponse>> getPendingEmployees(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<EmployeeResponse> result = employeeService.findPendingByStore(storeId).stream()
                .map(EmployeeResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 승인된 직원 목록 조회
    @GetMapping("/api/stores/{storeId}/employees")
    public ResponseEntity<List<EmployeeResponse>> getApprovedEmployees(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<EmployeeResponse> result = employeeService.findApprovedByStore(storeId).stream()
                .map(EmployeeResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 신청 승인
    @PostMapping("/api/stores/{storeId}/employees/{employeeId}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @Valid @RequestBody ApproveRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.approve(storeId, employeeId, request.getHourlyWage(), userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    // 관리자: 직원 정보 수정 (이름, 전화번호, 시급)
    @PatchMapping("/api/stores/{storeId}/employees/{employeeId}")
    public ResponseEntity<Void> updateEmployee(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @RequestBody UpdateEmployeeRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        employeeService.updateEmployee(storeId, employeeId, request.getName(), request.getPhoneNumber(), request.getHourlyWage(), userDetails.getUsername());
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
    static class UpdateEmployeeRequest {
        private String name;
        private String phoneNumber;
        private Integer hourlyWage;
    }

    @Data
    static class JoinRequest {
        @NotBlank(message = "이름을 입력해주세요.")
        private String name;
        private String phoneNumber;
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
    }

    @Data
    @AllArgsConstructor
    static class EmployeeResponse {
        private Long id;
        private String name;
        private String phoneNumber;
        private Integer hourlyWage;
        private String status;

        static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(
                    e.getId(),
                    e.getName(),
                    e.getPhoneNumber(),
                    e.getHourlyWage(),
                    e.getStatus().name()
            );
        }
    }
}
