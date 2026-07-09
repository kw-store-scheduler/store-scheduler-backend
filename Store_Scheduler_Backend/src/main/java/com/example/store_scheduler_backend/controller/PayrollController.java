package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.service.PayrollService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/stores/{storeId}/payroll")
@RequiredArgsConstructor
@Tag(name = "급여 API", description = "월별 직원 급여 자동 계산")
public class PayrollController {

    private final PayrollService payrollService;

    @Operation(summary = "월별 급여 계산", description = "해당 월의 근무 기록을 바탕으로 직원별 급여(기본급 + 주휴수당)를 계산합니다.")
    @GetMapping
    public ResponseEntity<List<PayrollService.EmployeePayroll>> getMonthlyPayroll(
            @PathVariable Long storeId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth yearMonth,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<PayrollService.EmployeePayroll> result =
                payrollService.calculateMonthlyPayroll(storeId, yearMonth, userDetails.getUsername());
        return ResponseEntity.ok(result);
    }
}
