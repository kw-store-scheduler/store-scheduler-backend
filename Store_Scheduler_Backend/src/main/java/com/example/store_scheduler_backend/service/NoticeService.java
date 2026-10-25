package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Notice;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.StoreTask;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.NoticeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.StoreTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// 하단 탭 "공지·업무": 매장 공지와 날짜별 업무 체크리스트
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NoticeService {

    private final StoreAccessGuard storeAccessGuard;
    private final NoticeRepository noticeRepository;
    private final StoreTaskRepository taskRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final ScheduleRepository scheduleRepository;
    private final FcmService fcmService;

    public List<Notice> findNotices(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return noticeRepository.findByStoreIdOrderByPinnedDescCreatedAtDesc(storeId);
    }

    public Optional<Notice> findLatest(Long storeId) {
        return noticeRepository.findByStoreIdOrderByPinnedDescCreatedAtDesc(storeId).stream()
                .max(Comparator.comparing(Notice::getCreatedAt));
    }

    @Transactional
    public Notice createNotice(Long storeId, String title, String content, boolean pinned, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        Notice notice = noticeRepository.save(new Notice(store, title, content, pinned));
        employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED)
                .forEach(e -> fcmService.send(e.getUser(), "새 공지", title));
        return notice;
    }

    @Transactional
    public void deleteNotice(Long storeId, Long noticeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        noticeRepository.delete(noticeRepository.findById(noticeId)
                .filter(n -> n.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공지입니다.")));
    }

    // 점주는 그날 전체 업무를 본다
    public List<StoreTask> findTasks(Long storeId, LocalDate date, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return taskRepository.findByStoreIdAndDateOrderByIdAsc(storeId, date);
    }

    // 직원은 나에게 맡겨진 업무와, 담당자 없이 내 근무 교대(또는 그날 전체)에 걸린 업무를 본다
    public List<StoreTask> findMyTasks(Long storeId, LocalDate date, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        List<Schedule> mine = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(me.getId(), date, date);
        return taskRepository.findByStoreIdAndDateOrderByIdAsc(storeId, date).stream()
                .filter(t -> t.getAssignee() != null
                        ? t.getAssignee().getId().equals(me.getId())
                        : !mine.isEmpty() && (t.getShift() == null || mine.stream().anyMatch(s -> sameTime(t.getShift(), s))))
                .toList();
    }

    @Transactional
    public StoreTask createTask(Long storeId, LocalDate date, String title, Long shiftId, Long assigneeId,
                                String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        Shift shift = shiftId == null ? null : shiftRepository.findById(shiftId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무 시간대입니다."));
        Employee assignee = assigneeId == null ? null : employeeRepository.findById(assigneeId)
                .filter(e -> e.getStore().getId().equals(storeId) && e.getStatus() == EmployeeStatus.APPROVED)
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 승인된 직원이 아닙니다."));
        return taskRepository.save(new StoreTask(store, date, title, shift, assignee));
    }

    @Transactional
    public void deleteTask(Long storeId, Long taskId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        taskRepository.delete(findTask(storeId, taskId));
    }

    @Transactional
    public StoreTask check(Long storeId, Long taskId, boolean done, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        StoreTask task = findTask(storeId, taskId);
        if (findMyTasks(storeId, task.getDate(), username).stream().noneMatch(t -> t.getId().equals(taskId))) {
            throw new IllegalArgumentException("내 업무가 아닙니다.");
        }
        task.check(me, done);
        return task;
    }

    private StoreTask findTask(Long storeId, Long taskId) {
        return taskRepository.findById(taskId)
                .filter(t -> t.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 업무입니다."));
    }

    private static boolean sameTime(Shift shift, Schedule schedule) {
        return shift.getStartTime().equals(schedule.getStartTime()) && shift.getEndTime().equals(schedule.getEndTime());
    }
}
