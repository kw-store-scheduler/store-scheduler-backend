package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.service.EmployeeService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping
    public ResponseEntity<List<EmployeeResponse>> getAllEmployees() {
        List<EmployeeResponse> result = employeeService.findEmployees().stream()
                .map(e -> new EmployeeResponse(
                        e.getId(),
                        e.getName(),
                        e.getPhoneNumber(),
                        e.getHourlyWage(),
                        e.getStatus().name(),
                        e.getStore() != null ? e.getStore().getName() : null
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Data
    @AllArgsConstructor
    static class EmployeeResponse {
        private Long id;
        private String name;
        private String phoneNumber;
        private Integer hourlyWage;
        private String status;
        private String storeName;
    }
}