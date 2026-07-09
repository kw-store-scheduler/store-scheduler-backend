package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.service.SkillService;
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
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    // 관리자: 매장에 스킬 등록
    @PostMapping("/api/stores/{storeId}/skills")
    public ResponseEntity<SkillResponse> createSkill(
            @PathVariable Long storeId,
            @Valid @RequestBody SkillRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        Skill skill = skillService.createSkill(storeId, request.getName(), userDetails.getUsername());
        return ResponseEntity.ok(SkillResponse.from(skill));
    }

    // 매장 스킬 목록 조회
    @GetMapping("/api/stores/{storeId}/skills")
    public ResponseEntity<List<SkillResponse>> getSkills(@PathVariable Long storeId) {
        List<SkillResponse> result = skillService.findByStore(storeId).stream()
                .map(SkillResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // 관리자: 스킬 삭제
    @DeleteMapping("/api/stores/{storeId}/skills/{skillId}")
    public ResponseEntity<Void> deleteSkill(
            @PathVariable Long storeId,
            @PathVariable Long skillId,
            @AuthenticationPrincipal UserDetails userDetails) {
        skillService.deleteSkill(storeId, skillId, userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    // 관리자: 직원에게 스킬 부여
    @PostMapping("/api/stores/{storeId}/employees/{employeeId}/skills/{skillId}")
    public ResponseEntity<Void> assignSkill(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @PathVariable Long skillId,
            @AuthenticationPrincipal UserDetails userDetails) {
        skillService.assignSkillToEmployee(storeId, employeeId, skillId, userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    // 관리자: 직원 스킬 제거
    @DeleteMapping("/api/stores/{storeId}/employees/{employeeId}/skills/{skillId}")
    public ResponseEntity<Void> removeSkill(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @PathVariable Long skillId,
            @AuthenticationPrincipal UserDetails userDetails) {
        skillService.removeSkillFromEmployee(storeId, employeeId, skillId, userDetails.getUsername());
        return ResponseEntity.ok().build();
    }

    @Data
    static class SkillRequest {
        @NotBlank(message = "스킬 이름을 입력해주세요.")
        private String name;
    }

    @Data
    @AllArgsConstructor
    static class SkillResponse {
        private Long id;
        private String name;

        static SkillResponse from(Skill skill) {
            return new SkillResponse(skill.getId(), skill.getName());
        }
    }
}
