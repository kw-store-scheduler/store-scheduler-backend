package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeChangeLog;
import com.example.store_scheduler_backend.domain.EmployeePermission;
import com.example.store_scheduler_backend.domain.EmployeeSkillLevel;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.FixedScheduleCategory;
import com.example.store_scheduler_backend.domain.SkillLevel;
import com.example.store_scheduler_backend.domain.SkillWindowRequirement;
import com.example.store_scheduler_backend.domain.StaffPosition;
import com.example.store_scheduler_backend.service.EmployeeProfileService;
import com.example.store_scheduler_backend.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

@RestController
@Tag(name = "역량·근무자 프로필 API", description = "역량 등급 신청·확정, 혼자 맡길 수 있는 일, 수습 기간, 변경 이력, 시간대별 필요 역량")
@RequiredArgsConstructor
public class EmployeeProfileController {

    private final EmployeeProfileService profileService;

    @Operation(summary = "내 역량", description = "매장 스킬마다 확정 등급(level, null이면 등급 미지정 보유, NONE이면 없음)과 신청 중인 등급")
    @GetMapping("/api/stores/{storeId}/my-skills")
    public ResponseEntity<MySkillsResponse> getMySkills(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(MySkillsResponse.from(profileService.getMine(storeId, userDetails.getUsername())));
    }

    @Operation(summary = "역량 신청", description = "매니저가 확정하기 전까지는 배치에 쓰이지 않습니다. 확정 등급과 같은 값을 보내면 신청이 취소됩니다.")
    @PutMapping("/api/stores/{storeId}/my-skills")
    public ResponseEntity<MySkillsResponse> requestLevels(
            @PathVariable Long storeId,
            @RequestBody List<LevelRequest> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(MySkillsResponse.from(profileService.requestLevels(storeId,
                request.stream().map(r -> new EmployeeProfileService.LevelInput(r.getSkillId(), r.getLevel())).toList(),
                userDetails.getUsername())));
    }

    // 관리자: 확인 대기 중인 역량 신청
    @GetMapping("/api/stores/{storeId}/skill-requests")
    public ResponseEntity<List<SkillRequestResponse>> getPendingRequests(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.findPendingRequests(storeId, userDetails.getUsername()).stream()
                .map(SkillRequestResponse::from).toList());
    }

    // 관리자: 직원 × 스킬 등급 표
    @GetMapping("/api/stores/{storeId}/skill-matrix")
    public ResponseEntity<List<MatrixRow>> getMatrix(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.getMatrix(storeId, userDetails.getUsername()).entrySet().stream()
                .map(e -> new MatrixRow(e.getKey().getId(), e.getKey().getDisplayName(),
                        e.getKey().isOnProbation(LocalDate.now()),
                        e.getValue().stream().map(SkillStateResponse::from).toList()))
                .toList());
    }

    @Operation(summary = "근무자 프로필", description = "점주용. 계약·시급·수습, 역량과 신청, 혼자 맡길 수 있는 일, 변경 이력")
    @GetMapping("/api/stores/{storeId}/employees/{employeeId}/profile")
    public ResponseEntity<ProfileResponse> getProfile(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ProfileResponse.from(profileService.getProfile(storeId, employeeId, userDetails.getUsername())));
    }

    @Operation(summary = "혼자 맡길 수 있는 일·수습 기간 변경", description = "보낸 항목만 바뀝니다. clearProbation=true면 수습을 끝냅니다. 바뀐 내용은 이력에 남고 직원에게 알림이 갑니다.")
    @PatchMapping("/api/stores/{storeId}/employees/{employeeId}/profile")
    public ResponseEntity<ProfileResponse> updateProfile(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @RequestBody ProfileRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ProfileResponse.from(profileService.updateProfile(storeId, employeeId, request.getPermissions(),
                request.getProbationUntil(), Boolean.TRUE.equals(request.getClearProbation()),
                request.getContractMinWeeklyHours(), request.getContractMaxWeeklyHours(), request.getPosition(),
                userDetails.getUsername())));
    }

    // 관리자: 등급 확정 (신청 승인 또는 직접 지정). NONE이면 보유 스킬에서 뺀다
    @PutMapping("/api/stores/{storeId}/employees/{employeeId}/skills/{skillId}/level")
    public ResponseEntity<ProfileResponse> setLevel(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @PathVariable Long skillId,
            @RequestBody LevelRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (request.getLevel() == null) {
            throw new IllegalArgumentException("등급을 골라주세요.");
        }
        return ResponseEntity.ok(ProfileResponse.from(
                profileService.setLevel(storeId, employeeId, skillId, request.getLevel(), userDetails.getUsername())));
    }

    // 관리자: 신청을 받아들이지 않고 기존 등급 유지
    @PostMapping("/api/stores/{storeId}/employees/{employeeId}/skills/{skillId}/keep")
    public ResponseEntity<ProfileResponse> keepLevel(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @PathVariable Long skillId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(ProfileResponse.from(profileService.keepLevel(storeId, employeeId, skillId, userDetails.getUsername())));
    }

    // 관리자: 직원 대신 고정 일정 입력 (통째로 교체)
    @GetMapping("/api/stores/{storeId}/employees/{employeeId}/fixed-schedules")
    public ResponseEntity<List<FixedScheduleResponse>> getFixedSchedules(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.findFixedSchedules(storeId, employeeId, userDetails.getUsername())
                .stream().map(FixedScheduleResponse::from).toList());
    }

    @PutMapping("/api/stores/{storeId}/employees/{employeeId}/fixed-schedules")
    public ResponseEntity<List<FixedScheduleResponse>> replaceFixedSchedules(
            @PathVariable Long storeId,
            @PathVariable Long employeeId,
            @RequestBody List<FixedScheduleRequest> request,
            @AuthenticationPrincipal UserDetails userDetails) {
        List<PreferenceService.FixedScheduleInput> inputs = request.stream()
                .map(r -> new PreferenceService.FixedScheduleInput(r.getDayOfWeek(), r.getStartTime(), r.getEndTime(), r.getLabel(),
                        r.getCategory(), r.getValidFrom(), r.getValidUntil()))
                .toList();
        return ResponseEntity.ok(profileService.replaceFixedSchedules(storeId, employeeId, inputs, userDetails.getUsername())
                .stream().map(FixedScheduleResponse::from).toList());
    }

    // 관리자/직원: 시간대별 필요 역량
    @GetMapping("/api/stores/{storeId}/skill-windows")
    public ResponseEntity<List<WindowResponse>> getWindows(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(profileService.findWindows(storeId, userDetails.getUsername()).stream()
                .map(WindowResponse::from).toList());
    }

    @Operation(summary = "시간대별 필요 역량 추가", description = "예: 피크 12:00-14:00 응대 HIGH 1명 필수, 화·금 10-12 발주 가능자(ORDERING) 1명. 시간대와 겹치는 교대 중 어디에든 있으면 충족. mandatory=false면 '우선'")
    @PostMapping("/api/stores/{storeId}/skill-windows")
    public ResponseEntity<WindowResponse> addWindow(
            @PathVariable Long storeId,
            @RequestBody WindowRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(WindowResponse.from(profileService.addWindow(storeId, new EmployeeProfileService.WindowInput(
                request.getLabel(), request.getStartTime(), request.getEndTime(), request.getSkillId(), request.getMinLevel(),
                request.getPermission(), request.getRequiredCount() != null ? request.getRequiredCount() : 1,
                !Boolean.FALSE.equals(request.getMandatory()), request.getDays()), userDetails.getUsername())));
    }

    @DeleteMapping("/api/stores/{storeId}/skill-windows/{windowId}")
    public ResponseEntity<Void> deleteWindow(
            @PathVariable Long storeId,
            @PathVariable Long windowId,
            @AuthenticationPrincipal UserDetails userDetails) {
        profileService.deleteWindow(storeId, windowId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    @Data
    static class LevelRequest {
        private Long skillId;
        private SkillLevel level;
    }

    @Data
    static class ProfileRequest {
        private List<EmployeePermission> permissions;
        private LocalDate probationUntil;
        private Boolean clearProbation;
        // 근로계약 주 근무 시간 범위 (둘 다 보내야 바뀐다)
        private Integer contractMinWeeklyHours;
        private Integer contractMaxWeeklyHours;
        // 정규직 직책 (MANAGER·SENIOR·JUNIOR)
        private StaffPosition position;
    }

    @Data
    static class FixedScheduleRequest {
        private DayOfWeek dayOfWeek;
        private LocalTime startTime;
        private LocalTime endTime;
        private String label;
        private FixedScheduleCategory category;
        private LocalDate validFrom;
        private LocalDate validUntil;
    }

    @Data
    static class WindowRequest {
        private String label;
        private LocalTime startTime;
        private LocalTime endTime;
        private Long skillId;
        private SkillLevel minLevel;
        private EmployeePermission permission;
        private Integer requiredCount;
        // 생략하면 필수
        private Boolean mandatory;
        // 생략하면 매일
        private List<DayOfWeek> days;
    }

    // status: CONFIRMED(확정), PENDING(확인 대기)
    record SkillStateResponse(Long skillId, String name, SkillLevel level, SkillLevel requestedLevel, String status) {
        static SkillStateResponse from(EmployeeProfileService.SkillState s) {
            return new SkillStateResponse(s.skill().getId(), s.skill().getName(), s.level(), s.requestedLevel(),
                    s.requestedLevel() != null ? "PENDING" : "CONFIRMED");
        }
    }

    record PermissionResponse(EmployeePermission code, String label, boolean allowed) {
    }

    static List<PermissionResponse> permissionStates(Employee e) {
        List<EmployeePermission> allowed = e.getPermissionList();
        return Arrays.stream(EmployeePermission.values())
                .map(p -> new PermissionResponse(p, p.getLabel(), allowed.contains(p)))
                .toList();
    }

    record MySkillsResponse(List<SkillStateResponse> skills, List<PermissionResponse> permissions, LocalDate probationUntil) {
        static MySkillsResponse from(EmployeeProfileService.Profile p) {
            return new MySkillsResponse(p.skills().stream().map(SkillStateResponse::from).toList(),
                    permissionStates(p.employee()), p.employee().getProbationUntil());
        }
    }

    record SkillRequestResponse(Long employeeId, String employeeName, Long skillId, String skillName, SkillLevel level,
                                SkillLevel requestedLevel, LocalDateTime requestedAt) {
        static SkillRequestResponse from(EmployeeSkillLevel r) {
            return new SkillRequestResponse(r.getEmployee().getId(), r.getEmployee().getDisplayName(), r.getSkill().getId(),
                    r.getSkill().getName(), EmployeeProfileService.effectiveLevel(r.getEmployee(), r.getSkill(), r),
                    r.getRequestedLevel(), r.getRequestedAt());
        }
    }

    record MatrixRow(Long employeeId, String name, boolean onProbation, List<SkillStateResponse> skills) {
    }

    record HistoryResponse(LocalDateTime changedAt, String description) {
        static HistoryResponse from(EmployeeChangeLog log) {
            return new HistoryResponse(log.getCreatedAt(), log.getDescription());
        }
    }

    record ProfileResponse(Long employeeId, String name, String displayName, EmploymentType employmentType,
                           StaffPosition position, LocalDate startDate,
                           Integer hourlyWage, Integer contractMinWeeklyHours, Integer contractMaxWeeklyHours,
                           LocalDate probationUntil, int promiseScore, List<SkillStateResponse> skills,
                           List<PermissionResponse> permissions, List<HistoryResponse> history) {
        static ProfileResponse from(EmployeeProfileService.Profile p) {
            Employee e = p.employee();
            return new ProfileResponse(e.getId(), e.getName(), e.getDisplayName(), e.getEmploymentType(), e.getPosition(),
                    e.getStartDate(),
                    e.getHourlyWage(), e.getContractMinWeeklyHours(), e.getContractMaxWeeklyHours(), e.getProbationUntil(),
                    e.getPromiseScore(),
                    p.skills().stream().map(SkillStateResponse::from).toList(), permissionStates(e),
                    p.history().stream().map(HistoryResponse::from).toList());
        }
    }

    record FixedScheduleResponse(Long id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String label,
                                 FixedScheduleCategory category, LocalDate validFrom, LocalDate validUntil) {
        static FixedScheduleResponse from(FixedSchedule f) {
            return new FixedScheduleResponse(f.getId(), f.getDayOfWeek(), f.getStartTime(), f.getEndTime(), f.getLabel(),
                    f.getCategory(), f.getValidFrom(), f.getValidUntil());
        }
    }

    record WindowResponse(Long id, String label, LocalTime startTime, LocalTime endTime, Long skillId, String skillName,
                          SkillLevel minLevel, EmployeePermission permission, int requiredCount, boolean mandatory,
                          List<DayOfWeek> days) {
        static WindowResponse from(SkillWindowRequirement w) {
            return new WindowResponse(w.getId(), w.getLabel(), w.getStartTime(), w.getEndTime(),
                    w.getSkill() != null ? w.getSkill().getId() : null, w.getSkill() != null ? w.getSkill().getName() : null,
                    w.getMinLevel(), w.getPermission(), w.getRequiredCount(), w.isMandatory(), w.getDayList());
        }
    }
}
