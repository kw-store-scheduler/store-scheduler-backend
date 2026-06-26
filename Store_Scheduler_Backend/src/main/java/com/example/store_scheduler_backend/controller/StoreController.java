package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.service.StoreService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    /**
     * 매장 등록 API
     */
    @PostMapping
    public ResponseEntity<Long> createStore(@RequestBody Store store) {
        Long storeId = storeService.registerStore(store);
        return ResponseEntity.ok(storeId);
    }

    /**
     * 전체 매장 조회 API (무한 루프 방지 고도화)
     */
    @GetMapping
    public ResponseEntity<List<StoreResponseDto>> getAllStores() {
        List<Store> findStores = storeService.findStores();

        // 엔티티 -> DTO 객체로 변환 (무한 참조 가능성 차단)
        List<StoreResponseDto> result = findStores.stream()
                .map(s -> new StoreResponseDto(s.getId(), s.getName(), s.getAddress()))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * 외부 노출용 (DTO)
     */
    @Data
    @AllArgsConstructor
    static class StoreResponseDto {
        private Long id;
        private String name;
        private String address;
    }
}