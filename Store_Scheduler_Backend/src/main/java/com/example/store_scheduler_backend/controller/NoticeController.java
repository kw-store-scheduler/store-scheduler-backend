package com.example.store_scheduler_backend.controller;

import com.example.store_scheduler_backend.domain.Notice;
import com.example.store_scheduler_backend.domain.StoreTask;
import com.example.store_scheduler_backend.service.NoticeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@Tag(name = "공지·업무 API", description = "매장 공지와 날짜별 업무 체크리스트")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    // 관리자/직원: 공지 목록 (고정 공지 먼저, 최신순)
    @GetMapping("/api/stores/{storeId}/notices")
    public ResponseEntity<List<NoticeResponse>> getNotices(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(noticeService.findNotices(storeId, userDetails.getUsername()).stream()
                .map(NoticeResponse::from).toList());
    }

    // 관리자: 공지 작성 → 직원 전체 알림
    @PostMapping("/api/stores/{storeId}/notices")
    public ResponseEntity<NoticeResponse> createNotice(
            @PathVariable Long storeId,
            @Valid @RequestBody NoticeRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(NoticeResponse.from(noticeService.createNotice(storeId, request.getTitle(),
                request.getContent(), Boolean.TRUE.equals(request.getPinned()), userDetails.getUsername())));
    }

    @DeleteMapping("/api/stores/{storeId}/notices/{noticeId}")
    public ResponseEntity<Void> deleteNotice(
            @PathVariable Long storeId,
            @PathVariable Long noticeId,
            @AuthenticationPrincipal UserDetails userDetails) {
        noticeService.deleteNotice(storeId, noticeId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // 관리자: 날짜별 업무 전체 (생략하면 오늘)
    @GetMapping("/api/stores/{storeId}/tasks")
    public ResponseEntity<List<TaskResponse>> getTasks(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(noticeService.findTasks(storeId, date != null ? date : LocalDate.now(), userDetails.getUsername())
                .stream().map(TaskResponse::from).toList());
    }

    // 직원: 오늘 내 업무 (생략하면 오늘)
    @GetMapping("/api/stores/{storeId}/tasks/my")
    public ResponseEntity<List<TaskResponse>> getMyTasks(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(noticeService.findMyTasks(storeId, date != null ? date : LocalDate.now(), userDetails.getUsername())
                .stream().map(TaskResponse::from).toList());
    }

    // 관리자: 업무 등록. shiftId·assigneeId를 생략하면 그날 근무자 모두의 업무
    @PostMapping("/api/stores/{storeId}/tasks")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable Long storeId,
            @Valid @RequestBody TaskRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TaskResponse.from(noticeService.createTask(storeId, request.getDate(), request.getTitle(),
                request.getShiftId(), request.getAssigneeId(), userDetails.getUsername())));
    }

    @DeleteMapping("/api/stores/{storeId}/tasks/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @PathVariable Long storeId,
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserDetails userDetails) {
        noticeService.deleteTask(storeId, taskId, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

    // 직원: 업무 완료 체크 / 해제
    @PatchMapping("/api/stores/{storeId}/tasks/{taskId}")
    public ResponseEntity<TaskResponse> check(
            @PathVariable Long storeId,
            @PathVariable Long taskId,
            @Valid @RequestBody CheckRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(TaskResponse.from(noticeService.check(storeId, taskId, request.getDone(), userDetails.getUsername())));
    }

    @Data
    static class NoticeRequest {
        @NotBlank(message = "제목을 입력해주세요.")
        private String title;
        private String content;
        private Boolean pinned;
    }

    @Data
    static class TaskRequest {
        @NotNull(message = "날짜를 입력해주세요.")
        private LocalDate date;
        @NotBlank(message = "업무 내용을 입력해주세요.")
        private String title;
        private Long shiftId;
        private Long assigneeId;
    }

    @Data
    static class CheckRequest {
        @NotNull(message = "완료 여부를 입력해주세요.")
        private Boolean done;
    }

    record NoticeResponse(Long id, String title, String content, boolean pinned, LocalDateTime createdAt) {
        static NoticeResponse from(Notice n) {
            return new NoticeResponse(n.getId(), n.getTitle(), n.getContent(), n.isPinned(), n.getCreatedAt());
        }
    }

    record TaskResponse(Long id, LocalDate date, String title, Long shiftId, String shiftName, Long assigneeId,
                        String assigneeName, boolean done, String doneByName, LocalDateTime doneAt) {
        static TaskResponse from(StoreTask t) {
            return new TaskResponse(t.getId(), t.getDate(), t.getTitle(),
                    t.getShift() != null ? t.getShift().getId() : null,
                    t.getShift() != null ? t.getShift().getName() : null,
                    t.getAssignee() != null ? t.getAssignee().getId() : null,
                    t.getAssignee() != null ? t.getAssignee().getDisplayName() : null,
                    t.isDone(), t.getDoneBy() != null ? t.getDoneBy().getDisplayName() : null, t.getDoneAt());
        }
    }
}
