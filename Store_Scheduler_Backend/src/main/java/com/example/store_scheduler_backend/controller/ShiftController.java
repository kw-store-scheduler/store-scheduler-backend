package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.service.ShiftService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.StoreRepository;

@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;
    private final StoreRepository storeRepository;

    @PostMapping
    public ResponseEntity<Long> createShift(@RequestBody Shift shift) {
        if (shift.getStore() != null && shift.getStore().getId() != null) {
            Store store = storeRepository.findById(shift.getStore().getId())
                    .orElseThrow(() -> new RuntimeException("해당 매장을 찾을 수 없습니다."));
            shift.setStore(store);
        }
        Long shiftId = shiftService.registerShift(shift);
        return ResponseEntity.ok(shiftId);
    }

    @GetMapping
    public ResponseEntity<List<ShiftResponseDto>> getAllShifts() {
        List<Shift> shifts = shiftService.findShifts();
        List<ShiftResponseDto> result = shifts.stream()
                .map(s -> new ShiftResponseDto(
                        s.getId(),
                        s.getName(),
                        s.getStartTime(),
                        s.getEndTime(),
                        s.getRequiredStaff()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Data
    @AllArgsConstructor
    static class ShiftResponseDto {
        private Long id;
        private String name;
        private java.time.LocalTime startTime;
        private java.time.LocalTime endTime;
        private Integer requiredStaff;
    }
}