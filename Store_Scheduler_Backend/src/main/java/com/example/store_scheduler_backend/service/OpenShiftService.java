package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.ApplicationStatus;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.OpenShift;
import com.example.store_scheduler_backend.domain.OpenShiftKind;
import com.example.store_scheduler_backend.domain.OpenShiftStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ShiftApplication;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.repository.OpenShiftRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftApplicationRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// 빈 근무 게시판. 결원 요청은 자동으로 올라가고, 점주는 추가 인원 모집 글을 올린다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OpenShiftService {

    private final OpenShiftRepository openShiftRepository;
    private final ShiftApplicationRepository applicationRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final ScheduleRepository scheduleRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;

    // eligible·blocker·expectedPay·myStatus는 직원이 볼 때만 채운다
    public record BoardItem(OpenShift openShift, int applicantCount, Boolean eligible, String blocker, Long expectedPay,
                            ApplicationStatus myStatus) {
    }

    public record Board(List<BoardItem> items, Integer myWeekDays, Integer myWeekHours) {
    }

    public record Broadcast(OpenShift openShift, int notifiedCount) {
    }

    // 결원 요청이 들어오면 게시판에 올리고 대신할 수 있는 직원들에게 알린다
    @Transactional
    public Broadcast postVacancy(SwapRequest request) {
        OpenShift openShift = openShiftRepository.save(OpenShift.vacancy(request));
        return new Broadcast(openShift, notifyEligible(openShift));
    }

    // 점주: 후보 전체에게 다시 알림. 게시판 글이 없으면 새로 올린다
    @Transactional
    public Broadcast broadcast(Long storeId, Long swapRequestId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = swapRequestRepository.findById(swapRequestId)
                .filter(r -> r.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 결원 요청입니다."));
        if (!request.isActive()) {
            throw new IllegalArgumentException("이미 처리된 결원 요청입니다.");
        }
        Optional<OpenShift> existing = openShiftRepository
                .findBySwapRequestIdInAndStatus(List.of(swapRequestId), OpenShiftStatus.OPEN).stream().findFirst();
        if (existing.isEmpty()) {
            return postVacancy(request);
        }
        return new Broadcast(existing.get(), notifyEligible(existing.get()));
    }

    // 결원 요청이 다른 경로(지정 대타 수락, 취소, 근무표 교체)로 끝나면 게시판 글도 닫는다
    @Transactional
    public void closeFor(Collection<Long> swapRequestIds, boolean filled) {
        if (swapRequestIds.isEmpty()) {
            return;
        }
        for (OpenShift openShift : openShiftRepository.findBySwapRequestIdInAndStatus(swapRequestIds, OpenShiftStatus.OPEN)) {
            if (filled) {
                openShift.fill();
            } else {
                openShift.cancel();
            }
            rejectRemaining(openShift);
        }
    }

    @Transactional
    public OpenShift createExtra(Long storeId, LocalDate workDate, LocalTime startTime, LocalTime endTime, String title,
                                 String note, int headcount, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        if (workDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("지난 날짜에는 모집 글을 올릴 수 없습니다.");
        }
        if (headcount < 1) {
            throw new IllegalArgumentException("모집 인원은 1명 이상이어야 합니다.");
        }
        OpenShift openShift = openShiftRepository.save(OpenShift.extra(store, workDate, startTime, endTime, title, note, headcount));
        notifyEligible(openShift);
        return openShift;
    }

    @Transactional
    public void cancelExtra(Long storeId, Long openShiftId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        OpenShift openShift = findOpen(storeId, openShiftId);
        if (openShift.getKind() != OpenShiftKind.EXTRA) {
            throw new IllegalArgumentException("결원 글은 결원 요청을 취소하면 함께 내려갑니다.");
        }
        openShift.cancel();
        rejectRemaining(openShift);
    }

    // 오늘 이후 열린 글. 점주는 전체 지원자 수만, 직원은 지원 가능 여부와 예상 수당까지 본다
    public Board getBoard(Long storeId, String username) {
        Store store = storeAccessGuard.verifyMember(storeId, username);
        boolean owner = store.getOwner().getUsername().equals(username);
        List<OpenShift> open = openShiftRepository
                .findByStoreIdAndWorkDateGreaterThanEqualOrderByWorkDateAscStartTimeAsc(storeId, LocalDate.now()).stream()
                .filter(OpenShift::isOpen)
                .toList();
        Map<Long, List<ShiftApplication>> applicationsByShift = applicationRepository
                .findByOpenShiftIdIn(open.stream().map(OpenShift::getId).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.getOpenShift().getId()));
        if (owner) {
            return new Board(open.stream()
                    .map(o -> new BoardItem(o, countApplied(applicationsByShift.get(o.getId())), null, null, null, null))
                    .toList(), null, null);
        }

        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        int wage = me.getHourlyWage() != null ? me.getHourlyWage() : 0;
        List<BoardItem> items = open.stream()
                .filter(o -> !isOwnVacancy(o, me))
                .map(o -> {
                    Optional<SubstituteFinder.Candidate> candidate = candidateOf(o, me);
                    ApplicationStatus myStatus = applicationsByShift.getOrDefault(o.getId(), List.of()).stream()
                            .filter(a -> a.getEmployee().getId().equals(me.getId()))
                            .map(ShiftApplication::getStatus)
                            .findFirst()
                            .orElse(null);
                    boolean eligible = candidate.map(SubstituteFinder.Candidate::eligible).orElse(false);
                    String blocker = eligible ? null : candidate.map(c -> c.reasons().get(0)).orElse("지원할 수 없는 근무");
                    return new BoardItem(o, countApplied(applicationsByShift.get(o.getId())), eligible, blocker,
                            Math.round(minutes(o.getStartTime(), o.getEndTime()) / 60.0 * wage), myStatus);
                })
                .toList();

        LocalDate weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Schedule> week = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                me.getId(), weekStart, weekStart.plusDays(6));
        int weekHours = (int) Math.round(week.stream().mapToLong(s -> minutes(s.getStartTime(), s.getEndTime())).sum() / 60.0);
        return new Board(items, (int) week.stream().map(Schedule::getWorkDate).distinct().count(), weekHours);
    }

    // 근무표 생성과 같은 규칙(휴무, 가용 시간, 하루 1교대, 주 52시간, 연속 5일, 미성년자)을 통과해야 지원할 수 있다
    @Transactional
    public ShiftApplication apply(Long storeId, Long openShiftId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        OpenShift openShift = findOpen(storeId, openShiftId);
        if (isOwnVacancy(openShift, me)) {
            throw new IllegalArgumentException("본인이 낸 결원에는 지원할 수 없습니다.");
        }
        SubstituteFinder.Candidate candidate = candidateOf(openShift, me)
                .orElseThrow(() -> new IllegalArgumentException("지원할 수 없는 근무입니다."));
        if (!candidate.eligible()) {
            throw new IllegalArgumentException("지원할 수 없는 근무입니다: " + candidate.reasons().get(0));
        }
        Optional<ShiftApplication> existing = applicationRepository.findByOpenShiftIdAndEmployeeId(openShiftId, me.getId());
        if (existing.isPresent() && existing.get().getStatus() == ApplicationStatus.APPLIED) {
            throw new IllegalArgumentException("이미 지원한 근무입니다.");
        }
        ShiftApplication application = existing.orElseGet(() -> new ShiftApplication(openShift, me));
        application.reapply();
        applicationRepository.save(application);
        fcmService.sendToDevice(openShift.getStore().getOwner().getDeviceToken(), "빈 근무 지원",
                me.getDisplayName() + "님이 " + openShift.getWorkDate() + " " + openShift.getStartTime() + " 근무에 지원했습니다.");
        return application;
    }

    @Transactional
    public void withdraw(Long storeId, Long openShiftId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        ShiftApplication application = applicationRepository.findByOpenShiftIdAndEmployeeId(openShiftId, me.getId())
                .filter(a -> a.getStatus() == ApplicationStatus.APPLIED)
                .orElseThrow(() -> new IllegalArgumentException("지원 중인 근무가 아닙니다."));
        application.withdraw();
    }

    public List<ShiftApplication> findApplications(Long storeId, Long openShiftId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        findInStore(storeId, openShiftId);
        return applicationRepository.findByOpenShiftIdOrderByAppliedAtAsc(openShiftId);
    }

    // 결원이면 그 근무 담당자를 바꾸고, 추가 모집이면 근무를 새로 만든다. 모집 인원이 차면 나머지 지원자는 거절
    @Transactional
    public ShiftApplication approve(Long storeId, Long openShiftId, Long applicationId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        OpenShift openShift = findOpen(storeId, openShiftId);
        ShiftApplication application = applicationRepository.findById(applicationId)
                .filter(a -> a.getOpenShift().getId().equals(openShiftId) && a.getStatus() == ApplicationStatus.APPLIED)
                .orElseThrow(() -> new IllegalArgumentException("승인할 수 있는 지원이 아닙니다."));
        Employee applicant = application.getEmployee();
        // 지원 이후 다른 근무가 잡혔을 수 있으므로 승인 시점에 다시 확인
        if (!candidateOf(openShift, applicant).map(SubstituteFinder.Candidate::eligible).orElse(false)) {
            throw new IllegalArgumentException("현재 근무 상황으로는 이 지원자를 배정할 수 없습니다.");
        }

        application.approve();
        if (openShift.getKind() == OpenShiftKind.VACANCY) {
            SwapRequest request = openShift.getSwapRequest();
            request.offer(applicant);
            request.accept();
            openShift.fill();
            fcmService.sendToDevice(request.getRequester().getUser().getDeviceToken(), "대타 확정",
                    request.getWorkDate() + " " + request.getStartTime() + " 근무를 " + applicant.getDisplayName() + "님이 대신합니다.");
        } else {
            Schedule schedule = new Schedule();
            schedule.setStore(openShift.getStore());
            schedule.setEmployee(applicant);
            schedule.setWorkDate(openShift.getWorkDate());
            schedule.setStartTime(openShift.getStartTime());
            schedule.setEndTime(openShift.getEndTime());
            schedule.setAssignmentReasons("빈 근무 게시판 지원: " + openShift.getTitle());
            scheduleRepository.save(schedule);
            long approved = applicationRepository.findByOpenShiftIdOrderByAppliedAtAsc(openShiftId).stream()
                    .filter(a -> a.getStatus() == ApplicationStatus.APPROVED)
                    .count();
            if (approved >= openShift.getHeadcount()) {
                openShift.fill();
            }
        }
        if (!openShift.isOpen()) {
            rejectRemaining(openShift);
        }
        fcmService.sendToDevice(applicant.getUser().getDeviceToken(), "빈 근무 승인",
                openShift.getWorkDate() + " " + openShift.getStartTime() + " 근무가 내 근무표에 추가되었습니다.");
        return application;
    }

    // 대타 추천 화면에서 후보별로 게시판 지원 상태를 보여주는 용도
    public Map<Long, ApplicationStatus> applicationStatusesFor(Long swapRequestId) {
        Map<Long, ApplicationStatus> result = new HashMap<>();
        List<Long> openShiftIds = openShiftRepository.findBySwapRequestId(swapRequestId).stream()
                .map(OpenShift::getId)
                .toList();
        applicationRepository.findByOpenShiftIdIn(openShiftIds)
                .forEach(a -> result.put(a.getEmployee().getId(), a.getStatus()));
        return result;
    }

    private int notifyEligible(OpenShift openShift) {
        Long excluded = openShift.getSwapRequest() != null ? openShift.getSwapRequest().getRequester().getId() : null;
        List<SubstituteFinder.Candidate> eligible = substituteFinder.find(openShift.getStore().getId(), openShift.getWorkDate(),
                        openShift.getStartTime(), openShift.getEndTime(), excluded).stream()
                .filter(SubstituteFinder.Candidate::eligible)
                .toList();
        String title = openShift.getKind() == OpenShiftKind.VACANCY ? "대타 구해요" : "추가 근무 모집";
        for (SubstituteFinder.Candidate c : eligible) {
            fcmService.sendToDevice(c.employee().getUser().getDeviceToken(), title,
                    openShift.getWorkDate() + " " + openShift.getStartTime() + "~" + openShift.getEndTime() + " " + openShift.getTitle());
        }
        return eligible.size();
    }

    private Optional<SubstituteFinder.Candidate> candidateOf(OpenShift openShift, Employee employee) {
        Long excluded = openShift.getSwapRequest() != null ? openShift.getSwapRequest().getRequester().getId() : null;
        return substituteFinder.find(openShift.getStore().getId(), openShift.getWorkDate(), openShift.getStartTime(),
                        openShift.getEndTime(), excluded).stream()
                .filter(c -> c.employee().getId().equals(employee.getId()))
                .findFirst();
    }

    private void rejectRemaining(OpenShift openShift) {
        applicationRepository.findByOpenShiftIdOrderByAppliedAtAsc(openShift.getId()).stream()
                .filter(a -> a.getStatus() == ApplicationStatus.APPLIED)
                .forEach(ShiftApplication::reject);
    }

    private static boolean isOwnVacancy(OpenShift openShift, Employee employee) {
        return openShift.getSwapRequest() != null && openShift.getSwapRequest().getRequester().getId().equals(employee.getId());
    }

    private static int countApplied(List<ShiftApplication> applications) {
        return applications == null ? 0
                : (int) applications.stream().filter(a -> a.getStatus() == ApplicationStatus.APPLIED).count();
    }

    private static long minutes(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        return minutes <= 0 ? minutes + 24 * 60 : minutes;
    }

    private OpenShift findOpen(Long storeId, Long openShiftId) {
        OpenShift openShift = findInStore(storeId, openShiftId);
        if (!openShift.isOpen()) {
            throw new IllegalArgumentException("마감된 근무입니다.");
        }
        return openShift;
    }

    private OpenShift findInStore(Long storeId, Long openShiftId) {
        return openShiftRepository.findById(openShiftId)
                .filter(o -> o.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무 글입니다."));
    }
}
