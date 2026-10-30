package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.ApplicationStatus;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.OpenShift;
import com.example.store_scheduler_backend.domain.OpenShiftKind;
import com.example.store_scheduler_backend.domain.OpenShiftStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ShiftApplication;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.SwapStatus;
import com.example.store_scheduler_backend.repository.OpenShiftRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftApplicationRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.Comparator;
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
    private final ScheduleRepository scheduleRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;
    private final SwapAssignment swapAssignment;
    private final SkillRepository skillRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final LaborPolicy laborPolicy;
    private final RowLock rowLock;

    // eligible·blocker·expectedPay·myStatus는 직원이 볼 때만 채운다
    public record BoardItem(OpenShift openShift, int applicantCount, Boolean eligible, String blocker, Long expectedPay,
                            ApplicationStatus myStatus) {
    }

    public record Board(List<BoardItem> items, Integer myWeekDays, Integer myWeekHours) {
    }

    // 점주 지원자 목록. 이번 달 추가 근무가 적은 순 → 이번 주 근무가 적은 순
    // weekHours는 이 근무를 포함한 이번 주 시간, 주 40시간을 넘는 부분은 연장근무로 50% 가산 (상시 5인 이상)
    public record Applicant(ShiftApplication application, int monthExtraCount, double weekHours, long expectedPay,
                            boolean overtime) {
    }



    public record Broadcast(OpenShift openShift, int notifiedCount) {
    }

    // 결원을 게시판에 올리고 대신할 수 있는 직원들에게 알린다. 이미 올라가 있으면 그 글을 돌려준다
    @Transactional
    public Broadcast postVacancy(SwapRequest request) {
        Optional<OpenShift> existing = openShiftRepository
                .findBySwapRequestIdInAndStatus(List.of(request.getId()), OpenShiftStatus.OPEN).stream().findFirst();
        if (existing.isPresent()) {
            return new Broadcast(existing.get(), 0);
        }
        OpenShift openShift = openShiftRepository.save(OpenShift.vacancy(request));
        return new Broadcast(openShift, notifyEligible(openShift));
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
                                 String note, int headcount, Long requiredSkillId, LocalDateTime applyDeadline,
                                 String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        Skill requiredSkill = requiredSkillId == null ? null : skillRepository.findById(requiredSkillId)
                .filter(sk -> sk.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 스킬이 아닙니다: " + requiredSkillId));
        if (workDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("지난 날짜에는 모집 글을 올릴 수 없습니다.");
        }
        if (headcount < 1) {
            throw new IllegalArgumentException("모집 인원은 1명 이상이어야 합니다.");
        }
        OpenShift openShift = openShiftRepository.save(OpenShift.extra(store, workDate, startTime, endTime, title, note,
                headcount, requiredSkill, applyDeadline));
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
                    Optional<String> blocker = blockerFor(o, me);
                    ApplicationStatus myStatus = applicationsByShift.getOrDefault(o.getId(), List.of()).stream()
                            .filter(a -> a.getEmployee().getId().equals(me.getId()))
                            .map(ShiftApplication::getStatus)
                            .findFirst()
                            .orElse(null);
                    return new BoardItem(o, countApplied(applicationsByShift.get(o.getId())), blocker.isEmpty(),
                            blocker.orElse(null),
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
        Optional<String> blocker = blockerFor(openShift, me);
        if (blocker.isPresent()) {
            throw new IllegalArgumentException("지원할 수 없는 근무입니다: " + blocker.get());
        }
        Optional<ShiftApplication> existing = applicationRepository.findByOpenShiftIdAndEmployeeId(openShiftId, me.getId());
        if (existing.isPresent() && existing.get().getStatus() == ApplicationStatus.APPLIED) {
            throw new IllegalArgumentException("이미 지원한 근무입니다.");
        }
        ShiftApplication application = existing.orElseGet(() -> new ShiftApplication(openShift, me));
        application.reapply();
        applicationRepository.save(application);
        fcmService.send(openShift.getStore().getOwner(), "빈 근무 지원",
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

    // 퇴사하는 직원의 after 이후 근무 지원을 거둔다
    @Transactional
    public void withdrawAll(Employee employee, LocalDate after) {
        applicationRepository.findByEmployeeIdAndStatus(employee.getId(), ApplicationStatus.APPLIED).stream()
                .filter(a -> a.getOpenShift().getWorkDate().isAfter(after))
                .forEach(ShiftApplication::withdraw);
    }

    public List<Applicant> findApplications(Long storeId, Long openShiftId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        OpenShift openShift = findInStore(storeId, openShiftId);
        boolean overtimeApplies = laborPolicy.premiumApplies(openShift.getStore());
        long shiftMinutes = minutes(openShift.getStartTime(), openShift.getEndTime());
        LocalDate weekStart = openShift.getWorkDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        YearMonth month = YearMonth.from(openShift.getWorkDate());
        return applicationRepository.findByOpenShiftIdOrderByAppliedAtAsc(openShiftId).stream()
                .map(a -> {
                    Employee e = a.getEmployee();
                    long weekMinutes = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                                    e.getId(), weekStart, weekStart.plusDays(6)).stream()
                            .mapToLong(s -> minutes(s.getStartTime(), s.getEndTime()))
                            .sum() + shiftMinutes;
                    long overtimeMinutes = overtimeApplies
                            ? Math.min(shiftMinutes, Math.max(0, weekMinutes - LaborPolicy.WEEKLY_REGULAR_MINUTES)) : 0;
                    int wage = e.getHourlyWage() != null ? e.getHourlyWage() : 0;
                    long pay = Math.round(shiftMinutes / 60.0 * wage + overtimeMinutes / 60.0 * wage * LaborPolicy.PREMIUM_RATE);
                    return new Applicant(a, monthExtraCount(e, month), Math.round(weekMinutes / 6.0) / 10.0, pay,
                            overtimeMinutes > 0);
                })
                .sorted(Comparator.comparing((Applicant x) -> x.application().getStatus() != ApplicationStatus.APPLIED)
                        .thenComparingInt(Applicant::monthExtraCount)
                        .thenComparingDouble(Applicant::weekHours))
                .toList();
    }

    // 게시판 승인과 대타 확정으로 이번 달에 추가로 맡은 근무 수
    private int monthExtraCount(Employee employee, YearMonth month) {
        long fromBoard = applicationRepository.findByEmployeeIdAndStatus(employee.getId(), ApplicationStatus.APPROVED).stream()
                .filter(a -> a.getOpenShift().getKind() == OpenShiftKind.EXTRA
                        && YearMonth.from(a.getOpenShift().getWorkDate()).equals(month))
                .count();
        long asSubstitute = swapRequestRepository.findBySubstituteIdAndStatusAndWorkDateBetween(employee.getId(),
                SwapStatus.ACCEPTED, month.atDay(1), month.atEndOfMonth()).size();
        return (int) (fromBoard + asSubstitute);
    }

    // 지원 마감, 필요 역량, 근무 규칙(휴무·가용 시간·하루 1교대·주 52시간·연속 근무·미성년자) 순으로 본다
    private Optional<String> blockerFor(OpenShift openShift, Employee employee) {
        if (openShift.isPastDeadline(LocalDateTime.now())) {
            return Optional.of("지원 마감");
        }
        if (!hasRequiredSkill(openShift, employee)) {
            return Optional.of("필요 역량(" + openShift.getRequiredSkill().getName() + ") 없음");
        }
        Optional<SubstituteFinder.Candidate> candidate = candidateOf(openShift, employee);
        if (candidate.isEmpty()) {
            return Optional.of("지원할 수 없는 근무");
        }
        return candidate.get().eligible() ? Optional.empty() : Optional.of(candidate.get().reasons().get(0));
    }

    // 결원이면 그 근무 담당자를 바꾸고, 추가 모집이면 근무를 새로 만든다. 모집 인원이 차면 나머지 지원자는 거절
    @Transactional
    public ShiftApplication approve(Long storeId, Long openShiftId, Long applicationId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        OpenShift openShift = findInStore(storeId, openShiftId);
        rowLock.lock(openShift);
        if (!openShift.isOpen()) {
            throw new IllegalArgumentException("마감된 근무입니다.");
        }
        if (openShift.getSwapRequest() != null) {
            rowLock.lock(openShift.getSwapRequest());
        }
        ShiftApplication application = applicationRepository.findById(applicationId)
                .filter(a -> a.getOpenShift().getId().equals(openShiftId) && a.getStatus() == ApplicationStatus.APPLIED)
                .orElseThrow(() -> new IllegalArgumentException("승인할 수 있는 지원이 아닙니다."));
        Employee applicant = application.getEmployee();
        // 지원 이후 다른 근무가 잡혔을 수 있으므로 승인 시점에 다시 확인
        if (!hasRequiredSkill(openShift, applicant)
                || !candidateOf(openShift, applicant).map(SubstituteFinder.Candidate::eligible).orElse(false)) {
            throw new IllegalArgumentException("현재 근무 상황으로는 이 지원자를 배정할 수 없습니다.");
        }

        application.approve();
        if (openShift.getKind() == OpenShiftKind.VACANCY) {
            swapAssignment.assign(openShift.getSwapRequest(), applicant);
            openShift.fill();
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
        fcmService.send(applicant.getUser(), "빈 근무 승인",
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
                .filter(c -> hasRequiredSkill(openShift, c.employee()))
                .toList();
        String title = openShift.getKind() == OpenShiftKind.VACANCY ? "대타 구해요" : "추가 근무 모집";
        for (SubstituteFinder.Candidate c : eligible) {
            fcmService.send(c.employee().getUser(), title,
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

    private static boolean hasRequiredSkill(OpenShift openShift, Employee employee) {
        Skill required = openShift.getRequiredSkill();
        return required == null || employee.getSkills().stream().anyMatch(sk -> sk.getId().equals(required.getId()));
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
