package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ShiftTrade;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.TradeStatus;
import com.example.store_scheduler_backend.domain.TradeType;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftTradeRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// 근무 넘기기·맞교환. 근무 48시간 전까지 요청하고, 동료 수락 → 사장님 승인 순서로 근무표가 바뀐다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShiftTradeService {

    public static final Duration MIN_NOTICE = Duration.ofHours(48);
    private static final int EXCHANGE_WINDOW_DAYS = 7;

    private final ShiftTradeRepository tradeRepository;
    private final ScheduleRepository scheduleRepository;
    private final SwapRequestRepository swapRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;

    // 넘기기는 동료별, 맞교환은 동료의 근무별 선택지. 안 되는 선택지는 사유와 함께
    public record Option(Employee employee, Schedule schedule, boolean available, String blocker) {
    }

    public record Options(Schedule mine, List<Option> handover, List<Option> exchange) {
    }

    public Options findOptions(Long storeId, Long scheduleId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        Schedule mine = findTradable(storeId, scheduleId, me);
        List<Option> handover = new ArrayList<>();
        for (SubstituteFinder.Candidate c : substituteFinder.find(storeId, mine.getWorkDate(), mine.getStartTime(),
                mine.getEndTime(), me.getId())) {
            handover.add(new Option(c.employee(), null, c.eligible(), c.eligible() ? null : c.reasons().get(0)));
        }
        List<Option> exchange = new ArrayList<>();
        LocalDate from = mine.getWorkDate().minusDays(EXCHANGE_WINDOW_DAYS);
        LocalDate to = mine.getWorkDate().plusDays(EXCHANGE_WINDOW_DAYS);
        for (Schedule theirs : scheduleRepository.findByStoreIdAndWorkDateBetween(storeId, from, to)) {
            if (theirs.getEmployee().getId().equals(me.getId()) || !hasNotice(theirs)
                    || sameSlot(theirs, mine) || hasActiveSwap(theirs)) {
                continue;
            }
            Optional<String> blocker = exchangeBlocker(storeId, mine, theirs);
            exchange.add(new Option(theirs.getEmployee(), theirs, blocker.isEmpty(), blocker.orElse(null)));
        }
        exchange.sort(Comparator.comparing((Option o) -> !o.available())
                .thenComparing(o -> o.schedule().getWorkDate())
                .thenComparing(o -> o.schedule().getStartTime()));
        return new Options(mine, handover, exchange);
    }

    @Transactional
    public ShiftTrade request(Long storeId, Long scheduleId, TradeType type, Long targetEmployeeId, Long targetScheduleId,
                              boolean recurring, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        Schedule mine = findTradable(storeId, scheduleId, me);
        boolean pending = tradeRepository.findByRequesterSchedule_IdInOrTargetSchedule_IdIn(List.of(scheduleId), List.of(scheduleId))
                .stream().anyMatch(ShiftTrade::isOpen);
        if (pending) {
            throw new IllegalArgumentException("이미 바꾸기 요청이 진행 중인 근무입니다.");
        }
        ShiftTrade trade;
        if (type == TradeType.HANDOVER) {
            Employee target = employeeRepository.findById(targetEmployeeId == null ? -1 : targetEmployeeId)
                    .filter(e -> e.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new IllegalArgumentException("넘겨받을 동료를 골라주세요."));
            requireNoBlocker(handoverBlocker(storeId, mine, target));
            trade = new ShiftTrade(TradeType.HANDOVER, mine, target, null, recurring);
        } else {
            Schedule theirs = scheduleRepository.findById(targetScheduleId == null ? -1 : targetScheduleId)
                    .filter(s -> s.getStore().getId().equals(storeId) && !s.getEmployee().getId().equals(me.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("바꿀 동료의 근무를 골라주세요."));
            if (!hasNotice(theirs) || hasActiveSwap(theirs)) {
                throw new IllegalArgumentException("이 근무는 지금 바꿀 수 없습니다.");
            }
            requireNoBlocker(exchangeBlocker(storeId, mine, theirs));
            trade = new ShiftTrade(TradeType.EXCHANGE, mine, theirs.getEmployee(), theirs, recurring);
        }
        ShiftTrade saved = tradeRepository.save(trade);
        fcmService.send(saved.getTarget().getUser(),
                type == TradeType.HANDOVER ? "근무 넘기기 요청" : "근무 맞교환 요청",
                me.getDisplayName() + "님이 " + mine.getWorkDate() + " " + mine.getStartTime() + " 근무를 "
                        + (type == TradeType.HANDOVER ? "넘기고 싶어 해요." : "바꾸고 싶어 해요."));
        return saved;
    }

    // 동료: 수락하면 사장님 승인 대기
    @Transactional
    public ShiftTrade respond(Long storeId, Long tradeId, boolean accept, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        ShiftTrade trade = findTrade(storeId, tradeId);
        if (!trade.getTarget().getId().equals(me.getId()) || trade.getStatus() != TradeStatus.REQUESTED) {
            throw new AccessDeniedException("본인에게 온 요청만 처리할 수 있습니다.");
        }
        if (!accept) {
            trade.resolve(TradeStatus.DECLINED);
            fcmService.send(trade.getRequester().getUser(), "요청 거절",
                    me.getDisplayName() + "님이 근무 바꾸기를 거절했어요.");
            return trade;
        }
        requireNoBlocker(currentBlocker(trade));
        trade.peerAccept();
        fcmService.send(trade.getStore().getOwner(), "근무 변경 승인 필요",
                trade.getRequester().getDisplayName() + "님과 " + me.getDisplayName() + "님의 근무 변경을 승인해주세요.");
        return trade;
    }

    // 사장님: 승인하면 근무표 담당자를 바꾼다. 매주 반복이면 그달 남은 같은 요일·시간 근무도 바꾼다
    @Transactional
    public ShiftTrade approve(Long storeId, Long tradeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        ShiftTrade trade = findTrade(storeId, tradeId);
        if (trade.getStatus() != TradeStatus.PEER_ACCEPTED) {
            throw new IllegalArgumentException("동료가 수락한 요청만 승인할 수 있습니다.");
        }
        requireNoBlocker(currentBlocker(trade));
        apply(trade.getType(), trade.getRequesterSchedule(), trade.getTargetSchedule(), trade.getTarget());
        if (trade.isRecurring()) {
            applyRecurring(storeId, trade);
        }
        trade.resolve(TradeStatus.APPROVED);
        String message = trade.getRequesterDate() + " 근무 변경이 승인되어 근무표와 수당이 바뀌었어요.";
        fcmService.send(trade.getRequester().getUser(), "근무 변경 승인", message);
        fcmService.send(trade.getTarget().getUser(), "근무 변경 승인", message);
        return trade;
    }

    @Transactional
    public ShiftTrade reject(Long storeId, Long tradeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        ShiftTrade trade = findTrade(storeId, tradeId);
        if (!trade.isOpen()) {
            throw new IllegalArgumentException("이미 처리된 요청입니다.");
        }
        trade.resolve(TradeStatus.REJECTED);
        fcmService.send(trade.getRequester().getUser(), "근무 변경 반려", "사장님이 근무 변경을 반려했어요.");
        return trade;
    }

    @Transactional
    public ShiftTrade cancel(Long storeId, Long tradeId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        ShiftTrade trade = findTrade(storeId, tradeId);
        if (!trade.getRequester().getId().equals(me.getId()) || !trade.isOpen()) {
            throw new IllegalArgumentException("진행 중인 본인 요청만 취소할 수 있습니다.");
        }
        trade.resolve(TradeStatus.CANCELLED);
        return trade;
    }

    public List<ShiftTrade> findMine(Long storeId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        return tradeRepository.findByStoreIdAndRequesterIdOrStoreIdAndTargetIdOrderByCreatedAtDesc(storeId, me.getId(), storeId, me.getId());
    }

    public List<ShiftTrade> findOpen(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return tradeRepository.findByStoreIdAndStatusInOrderByCreatedAtDesc(storeId,
                List.of(TradeStatus.REQUESTED, TradeStatus.PEER_ACCEPTED));
    }

    // 근무 삭제·근무표 교체 전에 호출
    @Transactional
    public void detachSchedules(Collection<Long> scheduleIds) {
        if (!scheduleIds.isEmpty()) {
            tradeRepository.findByRequesterSchedule_IdInOrTargetSchedule_IdIn(scheduleIds, scheduleIds)
                    .forEach(ShiftTrade::detachSchedules);
        }
    }

    private void applyRecurring(Long storeId, ShiftTrade trade) {
        YearMonth month = YearMonth.from(trade.getRequesterDate());
        for (int week = 1; ; week++) {
            LocalDate myDate = trade.getRequesterDate().plusWeeks(week);
            if (!YearMonth.from(myDate).equals(month)) {
                return;
            }
            Optional<Schedule> mine = slotOf(trade.getRequester(), myDate, trade.getRequesterSchedule());
            Optional<Schedule> theirs = trade.getType() == TradeType.EXCHANGE
                    ? slotOf(trade.getTarget(), trade.getTargetDate().plusWeeks(week), trade.getTargetSchedule())
                    : Optional.empty();
            if (mine.isEmpty() || (trade.getType() == TradeType.EXCHANGE && theirs.isEmpty())
                    || hasActiveSwap(mine.get())) {
                continue;
            }
            Optional<String> blocker = trade.getType() == TradeType.HANDOVER
                    ? handoverBlocker(storeId, mine.get(), trade.getTarget())
                    : exchangeBlocker(storeId, mine.get(), theirs.get());
            if (blocker.isEmpty()) {
                apply(trade.getType(), mine.get(), theirs.orElse(null), trade.getTarget());
            }
        }
    }

    private Optional<Schedule> slotOf(Employee employee, LocalDate date, Schedule like) {
        return scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(employee.getId(), date, date).stream()
                .filter(s -> s.getStartTime().equals(like.getStartTime()) && s.getEndTime().equals(like.getEndTime()))
                .findFirst();
    }

    private static void apply(TradeType type, Schedule mine, Schedule theirs, Employee target) {
        Employee requester = mine.getEmployee();
        mine.setEmployee(target);
        mine.setAssignmentReasons(type == TradeType.HANDOVER ? "근무 넘겨받음" : "근무 맞교환");
        if (type == TradeType.EXCHANGE) {
            theirs.setEmployee(requester);
            theirs.setAssignmentReasons("근무 맞교환");
        }
    }

    private Optional<String> currentBlocker(ShiftTrade trade) {
        if (trade.getRequesterSchedule() == null || (trade.getType() == TradeType.EXCHANGE && trade.getTargetSchedule() == null)) {
            return Optional.of("근무표가 바뀌어 요청한 근무가 없어졌습니다");
        }
        if (!hasNotice(trade.getRequesterSchedule())) {
            return Optional.of("근무 48시간 전이 지나 바꿀 수 없습니다");
        }
        Long storeId = trade.getStore().getId();
        return trade.getType() == TradeType.HANDOVER
                ? handoverBlocker(storeId, trade.getRequesterSchedule(), trade.getTarget())
                : exchangeBlocker(storeId, trade.getRequesterSchedule(), trade.getTargetSchedule());
    }

    private Optional<String> handoverBlocker(Long storeId, Schedule mine, Employee target) {
        return substituteFinder.blockerAfterChanges(storeId, target, mine.getWorkDate(), mine.getStartTime(), mine.getEndTime(),
                List.of(mine), List.of());
    }

    // 서로의 근무를 바꾼 상태에서 양쪽 모두 규칙을 지키는지
    private Optional<String> exchangeBlocker(Long storeId, Schedule mine, Schedule theirs) {
        Employee me = mine.getEmployee();
        Employee them = theirs.getEmployee();
        Optional<String> forThem = substituteFinder.blockerAfterChanges(storeId, them, mine.getWorkDate(), mine.getStartTime(),
                mine.getEndTime(), List.of(mine, theirs),
                List.of(new SubstituteFinder.WorkSlot(me, theirs.getWorkDate(), theirs.getStartTime(), theirs.getEndTime())));
        if (forThem.isPresent()) {
            return forThem.map(r -> them.getDisplayName() + ": " + r);
        }
        return substituteFinder.blockerAfterChanges(storeId, me, theirs.getWorkDate(), theirs.getStartTime(), theirs.getEndTime(),
                        List.of(mine, theirs),
                        List.of(new SubstituteFinder.WorkSlot(them, mine.getWorkDate(), mine.getStartTime(), mine.getEndTime())))
                .map(r -> "내 쪽: " + r);
    }

    private Schedule findTradable(Long storeId, Long scheduleId, Employee me) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무입니다."));
        if (!schedule.getEmployee().getId().equals(me.getId())) {
            throw new AccessDeniedException("본인 근무만 바꿀 수 있습니다.");
        }
        if (!hasNotice(schedule)) {
            throw new IllegalArgumentException("근무 48시간 전까지만 바꿀 수 있습니다. 급한 경우 결원 신고를 이용해주세요.");
        }
        if (hasActiveSwap(schedule)) {
            throw new IllegalArgumentException("결원 요청이 진행 중인 근무입니다.");
        }
        return schedule;
    }

    private static boolean hasNotice(Schedule schedule) {
        return !LocalDateTime.of(schedule.getWorkDate(), schedule.getStartTime()).isBefore(LocalDateTime.now().plus(MIN_NOTICE));
    }

    private boolean hasActiveSwap(Schedule schedule) {
        return swapRequestRepository.findBySchedule_IdIn(List.of(schedule.getId())).stream().anyMatch(SwapRequest::isActive);
    }

    private static boolean sameSlot(Schedule a, Schedule b) {
        return a.getWorkDate().equals(b.getWorkDate()) && a.getStartTime().equals(b.getStartTime())
                && a.getEndTime().equals(b.getEndTime());
    }

    private static void requireNoBlocker(Optional<String> blocker) {
        if (blocker.isPresent()) {
            throw new IllegalArgumentException("바꿀 수 없는 근무입니다: " + blocker.get());
        }
    }

    private ShiftTrade findTrade(Long storeId, Long tradeId) {
        return tradeRepository.findById(tradeId)
                .filter(t -> t.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 요청입니다."));
    }
}
