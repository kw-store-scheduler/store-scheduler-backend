package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.service.ShiftService;
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

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    // 관리자: 근무 시간대 등록
    @PostMapping("/api/stores/{storeId}/shifts")
    public ResponseEntity<ShiftResponse> createShift(
            @PathVariable Long storeId,
            @Valid @RequestBody ShiftRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Shift shift = shiftService.registerShift(
                storeId,
                request.getName(),
                request.getStartTime(),
                request.getEndTime(),
                request.getRequiredStaff(),
                request.getDayOfWeek(),
                userDetails.getUsername());
        return ResponseEntity.ok(ShiftResponse.from(shift, List.of()));
    }

    // 관리자/직원: 매장 근무 시간대 조회
    @GetMapping("/api/stores/{storeId}/shifts")
    public ResponseEntity<List<ShiftResponse>> getShifts(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        Map<Long, List<ShiftSkillRequirement>> requirements =
                shiftService.findSkillRequirementsByStore(storeId, userDetails.getUsername());
        List<ShiftResponse> result = shiftService.findByStore(storeId, userDetails.getUsername()).stream()
                .map(s -> ShiftResponse.from(s, requirements.getOrDefault(s.getId(), List.of())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 근무 시간대 필요 스킬 설정 (예: 오픈에 일본어 가능자 1명)
    @PutMapping("/api/stores/{storeId}/shifts/{shiftId}/skill-requirements")
    public ResponseEntity<List<SkillRequirementResponse>> replaceSkillRequirements(
            @PathVariable Long storeId,
            @PathVariable Long shiftId,
            @RequestBody List<SkillRequirementRequest> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Map<Long, Integer> countBySkillId = new LinkedHashMap<>();
        for (SkillRequirementRequest r : request) {
            // 목록 본문은 @Valid로 항목까지 검증되지 않아 직접 확인
            if (r.getSkillId() == null || r.getCount() == null || r.getCount() < 1) {
                throw new IllegalArgumentException("스킬과 1명 이상의 필요 인원을 입력해주세요.");
            }
            if (countBySkillId.put(r.getSkillId(), r.getCount()) != null) {
                throw new IllegalArgumentException("같은 스킬이 중복되었습니다: " + r.getSkillId());
            }
        }
        List<SkillRequirementResponse> result = shiftService.replaceSkillRequirements(
                        storeId, shiftId, countBySkillId, userDetails.getUsername()).stream()
                .map(SkillRequirementResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @Data
    static class ShiftRequest {
        @NotBlank(message = "근무 시간대 이름을 입력해주세요.")
        private String name;

        @NotNull(message = "시작 시간을 입력해주세요.")
        private LocalTime startTime;

        @NotNull(message = "종료 시간을 입력해주세요.")
        private LocalTime endTime;

        @NotNull(message = "필요 인원을 입력해주세요.")
        @Min(value = 1, message = "필요 인원은 1명 이상이어야 합니다.")
        private Integer requiredStaff;

        private DayOfWeek dayOfWeek;
    }

    @Data
    @AllArgsConstructor
    static class ShiftResponse {
        private Long id;
        private String name;
        private LocalTime startTime;
        private LocalTime endTime;
        private Integer requiredStaff;
        private DayOfWeek dayOfWeek;
        private List<SkillRequirementResponse> skillRequirements;

        static ShiftResponse from(Shift s, List<ShiftSkillRequirement> requirements) {
            return new ShiftResponse(s.getId(), s.getName(), s.getStartTime(),
                    s.getEndTime(), s.getRequiredStaff(), s.getDayOfWeek(),
                    requirements.stream().map(SkillRequirementResponse::from).collect(Collectors.toList()));
        }
    }

    @Data
    static class SkillRequirementRequest {
        private Long skillId;
        private Integer count;
    }

    @Data
    @AllArgsConstructor
    static class SkillRequirementResponse {
        private Long skillId;
        private String skillName;
        private int count;

        static SkillRequirementResponse from(ShiftSkillRequirement r) {
            return new SkillRequirementResponse(r.getSkill().getId(), r.getSkill().getName(), r.getRequiredCount());
        }
    }
}
