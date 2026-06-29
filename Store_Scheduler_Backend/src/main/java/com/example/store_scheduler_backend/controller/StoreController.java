package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.service.StoreService;
import jakarta.validation.Valid;
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

    @GetMapping
    public ResponseEntity<List<StoreResponse>> getAllStores() {
        List<StoreResponse> result = storeService.findStores().stream()
                .map(s -> new StoreResponse(s.getId(), s.getName(), s.getAddress(), s.getStoreCode()))
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
    }
}