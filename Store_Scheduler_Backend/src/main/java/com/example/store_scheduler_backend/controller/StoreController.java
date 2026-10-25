package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.service.StoreService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

    // 관리자: 매장 설정 (guaranteeMustDayOff: 대신할 사람이 없어도 필수 휴무 보장, customersPerStaff: 인력 제안용 1인당 객수). 보낸 항목만 변경
    @PatchMapping("/{storeId}/settings")
    public ResponseEntity<StoreResponse> updateSettings(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreSettingsRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(StoreResponse.from(
                storeService.updateSettings(storeId, request.getGuaranteeMustDayOff(), request.getCustomersPerStaff(),
                        userDetails.getUsername())));
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
        private Boolean guaranteeMustDayOff;
        @Min(value = 1, message = "1인당 객수는 1 이상이어야 합니다.")
        private Integer customersPerStaff;
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
        private int customersPerStaff;

        static StoreResponse from(Store store) {
            return new StoreResponse(store.getId(), store.getName(), store.getAddress(), store.getStoreCode(),
                    store.isMustDayOffGuaranteed(), store.getCustomersPerStaffOrDefault());
        }
    }
}