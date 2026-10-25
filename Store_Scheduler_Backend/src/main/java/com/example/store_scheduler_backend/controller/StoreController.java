package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.service.StoreService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "매장 API", description = "매장 등록·조회 및 매장 코드 발급")
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @PostMapping
    public ResponseEntity<StoreCreateResponse> createStore(
            @Valid @RequestBody StoreCreateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Store store = storeService.registerStore(request.getName(), request.getAddress(), userDetails.getUsername());
        return ResponseEntity.ok(new StoreCreateResponse(store.getId(), store.getStoreCode()));
    }

    @PostMapping("/{storeId}/reissue-code")
    public ResponseEntity<StoreCreateResponse> reissueCode(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        String newCode = storeService.reissueCode(storeId, userDetails.getUsername());
        return ResponseEntity.ok(new StoreCreateResponse(storeId, newCode));
    }

    // 관리자: 근무표 생성 규칙 설정 (guaranteeMustDayOff: 대신할 사람이 없어도 필수 휴무 보장)
    @PatchMapping("/{storeId}/settings")
    public ResponseEntity<StoreResponse> updateSettings(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreSettingsRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(StoreResponse.from(
                storeService.updateSettings(storeId, request.getGuaranteeMustDayOff(), userDetails.getUsername())));
    }

    // 관리자: 본인이 소유한 매장 목록 조회
    @GetMapping
    public ResponseEntity<List<StoreResponse>> getMyStores(@AuthenticationPrincipal UserDetails userDetails) {
        List<StoreResponse> result = storeService.findMyStores(userDetails.getUsername()).stream()
                .map(StoreResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Data
    static class StoreCreateRequest {
        @NotBlank(message = "매장 이름을 입력해주세요.")
        private String name;
        private String address;
    }

    @Data
    static class StoreSettingsRequest {
        @NotNull(message = "guaranteeMustDayOff 값을 입력해주세요.")
        private Boolean guaranteeMustDayOff;
    }

    @Data
    @AllArgsConstructor
    static class StoreCreateResponse {
        private Long id;
        private String storeCode;
    }

    @Data
    @AllArgsConstructor
    static class StoreResponse {
        private Long id;
        private String name;
        private String address;
        private String storeCode;
        private boolean guaranteeMustDayOff;

        static StoreResponse from(Store store) {
            return new StoreResponse(store.getId(), store.getName(), store.getAddress(), store.getStoreCode(),
                    store.isMustDayOffGuaranteed());
        }
    }
}