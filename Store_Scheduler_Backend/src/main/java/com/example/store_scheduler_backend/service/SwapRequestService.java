package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.AbsenceReason;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.OfferStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapOffer;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.domain.SwapStatus;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.SwapOfferRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 결원 신고 → 대타 요청(동료 여러 명에게 동시에) → 먼저 수락한 1명 → 사장님 승인 → 근무표 반영
// 사장님이 보낸 요청은 수락하면 바로 확정되고, 30분 안에 아무도 수락하지 않으면 빈 근무 게시판으로 넘어간다
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SwapRequestService {

    public static final Duration OFFER_TIMEOUT = Duration.ofMinutes(30);

    private final SwapRequestRepository swapRequestRepository;
    private final SwapOfferRepository offerRepository;
    private final ScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SubstituteFinder substituteFinder;
    private final FcmService fcmService;
    private final OpenShiftService openShiftService;
    private final SwapAssignment swapAssignment;

    // 사장님 화면의 후보별 연락 상태. 대타 요청 상태(SENT 등)가 게시판 지원 상태보다 우선
    public record Contact(String status, LocalDateTime sentAt) {
    }

    // 대타 요청을 받은 직원 화면(W08)
    public record OfferView(SwapOffer offer, long expectedPay, int weekDaysBefore, int weekDaysAfter,
                            List<String> reasons, long sentCount) {
    }

    // 직원: 본인 근무에 대한 결원 요청
    @Transactional
    public SwapRequest request(Long storeId, Long scheduleId, String reason, String username) {
        return request(storeId, scheduleId, reason, null, null, username);
    }

    // partialFrom을 주면 그 시각부터만 빠진다 (예: 11시부터)
    @Transactional
    public SwapRequest request(Long storeId, Long scheduleId, String reason, AbsenceReason category, LocalTime partialFrom,
                               String username) {
        Employee requester = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무입니다."));
        if (!schedule.getEmployee().getId().equals(requester.getId())) {
            throw new AccessDeniedException("본인 근무에 대해서만 결원 요청을 할 수 있습니다.");
        }
        if (schedule.getWorkDate().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("이미 지난 근무입니다.");
        }
        if (partialFrom != null && !(schedule.getEndTime().isAfter(schedule.getStartTime())
                && partialFrom.isAfter(schedule.getStartTime()) && partialFrom.isBefore(schedule.getEndTime()))) {
            throw new IllegalArgumentException("일부만 빠지는 시각은 근무 시작과 종료 사이여야 합니다.");
        }
        boolean alreadyRequested = swapRequestRepository.findBySchedule_IdIn(List.of(scheduleId)).stream()
                .anyMatch(SwapRequest::isActive);
        if (alreadyRequested) {
            throw new IllegalArgumentException("이미 결원 요청이 진행 중인 근무입니다.");
        }

        SwapRequest saved = swapRequestRepository.save(new SwapRequest(schedule, reason, category, partialFrom));
        Store store = schedule.getStore();
        fcmService.send(store.getOwner(), "결원 요청",
                requester.getDisplayName() + "님이 " + schedule.getWorkDate() + " " + saved.getStartTime() + " 근무에 결원을 요청했습니다.");
        return saved;
    }

    // 관리자: 매장 결원 요청 목록
    public List<SwapRequest> findByStore(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return swapRequestRepository.findByStoreIdOrderByCreatedAtDesc(storeId);
    }

    // 사장님과 요청한 직원이 본다. 가능한 직원 우선, 불가능한 직원은 사유 포함
    public List<SubstituteFinder.Candidate> findCandidates(Long storeId, Long requestId, String username) {
        SwapRequest request = findRequestForParty(storeId, requestId, username);
        return substituteFinder.find(storeId, request.getWorkDate(), request.getStartTime(), request.getEndTime(),
                request.getRequester().getId());
    }

    public boolean isOwner(Long storeId, String username) {
        return storeAccessGuard.getStore(storeId).getOwner().getUsername().equals(username);
    }

    public Map<Long, Contact> findCandidateContacts(Long storeId, Long requestId, String username) {
        SwapRequest request = findRequestForParty(storeId, requestId, username);
        Map<Long, Contact> contacts = new HashMap<>();
        openShiftService.applicationStatusesFor(requestId)
                .forEach((employeeId, status) -> contacts.put(employeeId, new Contact(status.name(), null)));
        offerRepository.findBySwapRequestIdOrderBySentAtAsc(requestId)
                .forEach(o -> contacts.put(o.getCandidate().getId(), new Contact(o.getStatus().name(), o.getSentAt())));
        if (request.getStatus() == SwapStatus.PENDING_APPROVAL) {
            contacts.put(request.getSubstitute().getId(), new Contact(SwapStatus.PENDING_APPROVAL.name(), null));
        }
        return contacts;
    }

    // 요청한 직원이나 사장님: 고른 동료들에게 대타 요청. 먼저 수락한 한 명으로 정해진다
    @Transactional
    public SwapRequest sendOffers(Long storeId, Long requestId, List<Long> employeeIds, String username) {
        SwapRequest request = findRequestForParty(storeId, requestId, username);
        boolean byOwner = isOwner(storeId, username);
        if (!request.isActive() || request.getStatus() == SwapStatus.PENDING_APPROVAL) {
            throw new IllegalArgumentException(request.getStatus() == SwapStatus.PENDING_APPROVAL
                    ? "이미 수락한 대타가 사장님 승인을 기다리고 있습니다." : "이미 처리된 결원 요청입니다.");
        }
        if (employeeIds == null || employeeIds.isEmpty()) {
            throw new IllegalArgumentException("대타를 요청할 동료를 골라주세요.");
        }
        List<Long> eligible = substituteFinder.find(storeId, request.getWorkDate(), request.getStartTime(),
                        request.getEndTime(), request.getRequester().getId()).stream()
                .filter(SubstituteFinder.Candidate::eligible)
                .map(c -> c.employee().getId())
                .toList();
        for (Long employeeId : employeeIds.stream().distinct().toList()) {
            if (!eligible.contains(employeeId)) {
                throw new IllegalArgumentException("해당 직원은 이 근무를 대신할 수 없습니다. 대타 추천 목록을 확인해주세요.");
            }
            Employee candidate = employeeRepository.findById(employeeId).orElseThrow();
            SwapOffer offer = offerRepository.findBySwapRequestIdAndCandidateId(requestId, employeeId)
                    .orElseGet(() -> new SwapOffer(request, candidate, byOwner));
            offer.resend(byOwner);
            offerRepository.save(offer);
            fcmService.send(candidate.getUser(), "대타 요청",
                    request.getWorkDate() + " " + request.getStartTime() + "~" + request.getEndTime() + " 근무 대타를 부탁받았어요.");
        }
        request.markOffered();
        return request;
    }

    // 관리자: 한 명에게 대타 지정 (기존 방식). 수락하면 바로 확정
    @Transactional
    public SwapRequest offer(Long storeId, Long requestId, Long substituteId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return sendOffers(storeId, requestId, List.of(substituteId), managerUsername);
    }

    // 관리자: 가능한 직원 전원에게 대타 요청
    @Transactional
    public int broadcast(Long storeId, Long requestId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findActiveRequest(storeId, requestId);
        List<Long> eligible = substituteFinder.find(storeId, request.getWorkDate(), request.getStartTime(),
                        request.getEndTime(), request.getRequester().getId()).stream()
                .filter(SubstituteFinder.Candidate::eligible)
                .map(c -> c.employee().getId())
                .toList();
        if (eligible.isEmpty()) {
            return 0;
        }
        sendOffers(storeId, requestId, eligible, managerUsername);
        return eligible.size();
    }

    // 요청한 직원이나 사장님: 빈 근무 게시판에 올리기
    @Transactional
    public OpenShiftService.Broadcast postToBoard(Long storeId, Long requestId, String username) {
        SwapRequest request = findRequestForParty(storeId, requestId, username);
        if (!request.isActive()) {
            throw new IllegalArgumentException("이미 처리된 결원 요청입니다.");
        }
        return openShiftService.postVacancy(request);
    }

    // 대타 요청을 받은 직원: 수락 또는 거절
    @Transactional
    public SwapRequest respond(Long storeId, Long offerId, boolean accept, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        SwapOffer offer = offerRepository.findById(offerId)
                .filter(o -> o.getCandidate().getId().equals(me.getId()) && o.getSwapRequest().getStore().getId().equals(storeId))
                .orElseThrow(() -> new AccessDeniedException("본인에게 온 대타 요청만 처리할 수 있습니다."));
        return respond(offer, accept);
    }

    // 기존 방식: 결원 요청 번호로 수락
    @Transactional
    public SwapRequest accept(Long storeId, Long requestId, String username) {
        return respond(findMyPendingOffer(storeId, requestId, username), true);
    }

    @Transactional
    public SwapRequest decline(Long storeId, Long requestId, String username) {
        return respond(findMyPendingOffer(storeId, requestId, username), false);
    }

    // 관리자: 직원끼리 구한 대타 승인
    @Transactional
    public SwapRequest approve(Long storeId, Long requestId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findRequest(storeId, requestId);
        if (request.getStatus() != SwapStatus.PENDING_APPROVAL) {
            throw new IllegalArgumentException("승인을 기다리는 대타가 없습니다.");
        }
        Employee substitute = request.getSubstitute();
        if (!isEligible(request, substitute.getId())) {
            throw new IllegalArgumentException("현재 근무 상황으로는 이 대타를 배정할 수 없습니다.");
        }
        swapAssignment.assign(request, substitute);
        openShiftService.closeFor(List.of(requestId), true);
        return request;
    }

    // 관리자: 직원끼리 구한 대타를 받아들이지 않음 → 다시 대타를 찾는 상태
    @Transactional
    public SwapRequest rejectSubstitute(Long storeId, Long requestId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findRequest(storeId, requestId);
        if (request.getStatus() != SwapStatus.PENDING_APPROVAL) {
            throw new IllegalArgumentException("승인을 기다리는 대타가 없습니다.");
        }
        Employee substitute = request.getSubstitute();
        request.reopen();
        fcmService.send(substitute.getUser(), "대타 미승인",
                request.getWorkDate() + " " + request.getStartTime() + " 대타는 사장님이 다른 방법으로 채우기로 했어요.");
        fcmService.send(request.getRequester().getUser(), "대타 미승인",
                "사장님이 대타를 승인하지 않았어요. 다른 동료에게 요청하거나 게시판에 올려주세요.");
        return request;
    }

    // 관리자: 직접 배정 (요청·수락 없이 바로 확정)
    @Transactional
    public SwapRequest assignDirectly(Long storeId, Long requestId, Long employeeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        SwapRequest request = findActiveRequest(storeId, requestId);
        if (!isEligible(request, employeeId)) {
            throw new IllegalArgumentException("해당 직원은 이 근무를 대신할 수 없습니다. 대타 추천 목록을 확인해주세요.");
        }
        swapAssignment.assign(request, employeeRepository.findById(employeeId).orElseThrow());
        openShiftService.closeFor(List.of(requestId), true);
        return request;
    }

    // 요청 직원: 대타가 확정되기 전이면 취소 가능
    @Transactional
    public SwapRequest cancel(Long storeId, Long requestId, String username) {
        Employee employee = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        SwapRequest request = findActiveRequest(storeId, requestId);
        if (!request.getRequester().getId().equals(employee.getId())) {
            throw new AccessDeniedException("본인이 요청한 결원만 취소할 수 있습니다.");
        }
        request.cancel();
        swapAssignment.closePendingOffers(request);
        openShiftService.closeFor(List.of(request.getId()), false);
        return request;
    }

    // 직원: 나에게 온 대타 요청 (예상 급여, 이번 주 근무일 변화, 나에게 온 이유)
    public List<OfferView> findMyOffers(Long storeId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        int wage = me.getHourlyWage() != null ? me.getHourlyWage() : 0;
        List<OfferView> views = new ArrayList<>();
        for (SwapOffer offer : offerRepository.findByCandidateIdAndStatusOrderBySentAtDesc(me.getId(), OfferStatus.SENT)) {
            SwapRequest request = offer.getSwapRequest();
            if (!request.getStore().getId().equals(storeId) || !request.isActive()) {
                continue;
            }
            SubstituteFinder.Candidate candidate = substituteFinder.find(storeId, request.getWorkDate(), request.getStartTime(),
                            request.getEndTime(), request.getRequester().getId()).stream()
                    .filter(c -> c.employee().getId().equals(me.getId()))
                    .findFirst()
                    .orElse(null);
            LocalDate weekStart = request.getWorkDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            List<Schedule> week = scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(
                    me.getId(), weekStart, weekStart.plusDays(6));
            int daysBefore = (int) week.stream().map(Schedule::getWorkDate).distinct().count();
            boolean newDay = week.stream().noneMatch(s -> s.getWorkDate().equals(request.getWorkDate()));
            long sentCount = offerRepository.findBySwapRequestIdOrderBySentAtAsc(request.getId()).stream()
                    .filter(o -> o.getStatus() == OfferStatus.SENT)
                    .count();
            views.add(new OfferView(offer, Math.round(minutes(request.getStartTime(), request.getEndTime()) / 60.0 * wage),
                    daysBefore, daysBefore + (newDay ? 1 : 0), whyMe(candidate), sentCount));
        }
        return views;
    }

    // 근무가 삭제·교체되기 전에 호출해 진행 중인 요청을 정리한다
    @Transactional
    public void detachSchedules(Collection<Schedule> schedules) {
        if (schedules.isEmpty()) {
            return;
        }
        List<Long> ids = schedules.stream().map(Schedule::getId).toList();
        List<SwapRequest> requests = swapRequestRepository.findBySchedule_IdIn(ids);
        openShiftService.closeFor(requests.stream().filter(SwapRequest::isActive).map(SwapRequest::getId).toList(), false);
        requests.stream().filter(SwapRequest::isActive).forEach(swapAssignment::closePendingOffers);
        requests.forEach(SwapRequest::detachSchedule);
    }

    // 30분 안에 아무도 수락하지 않은 요청은 만료시키고 게시판으로 넘긴다
    @Transactional
    public int expireStaleOffers(LocalDateTime now) {
        Map<SwapRequest, List<SwapOffer>> stale = offerRepository
                .findByStatusAndSentAtBefore(OfferStatus.SENT, now.minus(OFFER_TIMEOUT)).stream()
                .collect(Collectors.groupingBy(SwapOffer::getSwapRequest));
        int moved = 0;
        for (Map.Entry<SwapRequest, List<SwapOffer>> entry : stale.entrySet()) {
            entry.getValue().forEach(o -> o.respond(OfferStatus.EXPIRED));
            SwapRequest request = entry.getKey();
            boolean stillWaiting = offerRepository.findBySwapRequestIdOrderBySentAtAsc(request.getId()).stream()
                    .anyMatch(SwapOffer::isPending);
            if (request.getStatus() == SwapStatus.OFFERED && !stillWaiting) {
                request.reopen();
                openShiftService.postVacancy(request);
                fcmService.send(request.getRequester().getUser(), "게시판으로 넘겼어요",
                        "30분 동안 대타 수락이 없어 빈 근무 게시판에 올렸어요.");
                moved++;
            }
        }
        return moved;
    }

    // 근무일이 지나도록 대타를 못 구한 요청을 정리한다. 개인 사정이면 약속 점수 -1
    @Transactional
    public int settlePastRequests(LocalDate today) {
        int settled = 0;
        for (SwapRequest request : swapRequestRepository.findByStatusInAndWorkDateBefore(
                List.of(SwapStatus.REQUESTED, SwapStatus.OFFERED, SwapStatus.PENDING_APPROVAL), today)) {
            request.cancel();
            swapAssignment.closePendingOffers(request);
            openShiftService.closeFor(List.of(request.getId()), false);
            if (request.getReasonCategory() == AbsenceReason.PERSONAL) {
                request.getRequester().losePromisePoint();
            }
            settled++;
        }
        return settled;
    }

    // 매달 1일: 약속 점수를 절반씩 회복
    @Transactional
    public void recoverPromiseScores() {
        employeeRepository.findByStatus(EmployeeStatus.APPROVED).forEach(Employee::recoverPromiseScore);
    }

    private SwapRequest respond(SwapOffer offer, boolean accept) {
        if (!offer.isPending()) {
            throw new IllegalArgumentException("이미 응답했거나 마감된 대타 요청입니다.");
        }
        SwapRequest request = offer.getSwapRequest();
        if (!accept) {
            offer.respond(OfferStatus.DECLINED);
            boolean stillWaiting = offerRepository.findBySwapRequestIdOrderBySentAtAsc(request.getId()).stream()
                    .anyMatch(SwapOffer::isPending);
            if (request.getStatus() == SwapStatus.OFFERED && !stillWaiting) {
                request.reopen();
            }
            fcmService.send(request.getStore().getOwner(), "대타 거절",
                    offer.getCandidate().getDisplayName() + "님이 " + request.getWorkDate() + " 근무 대타를 거절했습니다.");
            return request;
        }
        if (!request.isActive() || request.getStatus() == SwapStatus.PENDING_APPROVAL) {
            throw new IllegalArgumentException("다른 동료가 먼저 수락했거나 이미 처리된 요청입니다.");
        }
        // 요청 이후 다른 근무가 잡혔을 수 있으므로 수락 시점에 다시 확인
        if (!isEligible(request, offer.getCandidate().getId())) {
            throw new IllegalArgumentException("현재 근무 상황으로는 이 근무를 대신할 수 없습니다.");
        }
        offer.respond(OfferStatus.ACCEPTED);
        swapAssignment.closePendingOffers(request);
        if (offer.isSentByOwner()) {
            swapAssignment.assign(request, offer.getCandidate());
            openShiftService.closeFor(List.of(request.getId()), true);
        } else {
            request.awaitApproval(offer.getCandidate());
            fcmService.send(request.getStore().getOwner(), "대타 승인 필요",
                    offer.getCandidate().getDisplayName() + "님이 " + request.getRequester().getDisplayName() + "님의 "
                            + request.getWorkDate() + " 근무 대타를 수락했어요. 승인해주세요.");
        }
        return request;
    }

    // 근무표 생성과 같은 규칙을 통과했다는 것을 직원 말투로
    private static List<String> whyMe(SubstituteFinder.Candidate candidate) {
        List<String> reasons = new ArrayList<>();
        if (candidate == null) {
            return reasons;
        }
        if (candidate.reasons().stream().anyMatch(r -> r.startsWith("희망 제출"))) {
            reasons.add("이 시간이 가능하다고 신청했어요");
        } else if (candidate.reasons().stream().anyMatch(r -> r.startsWith("가용 시간"))) {
            reasons.add("등록한 가용 시간 안이에요");
        }
        if (candidate.eligible()) {
            reasons.add("주 52시간·연속 근무 한도 안이에요");
        }
        if (!candidate.coveredSkills().isEmpty()) {
            reasons.add("필요한 " + String.join(", ", candidate.coveredSkills()) + " 역량이 있어요");
        }
        return reasons;
    }

    private SwapOffer findMyPendingOffer(Long storeId, Long requestId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        findRequest(storeId, requestId);
        return offerRepository.findBySwapRequestIdAndCandidateId(requestId, me.getId())
                .filter(SwapOffer::isPending)
                .orElseThrow(() -> new AccessDeniedException("본인에게 온 대타 요청만 처리할 수 있습니다."));
    }

    // 사장님 또는 요청한 직원
    private SwapRequest findRequestForParty(Long storeId, Long requestId, String username) {
        SwapRequest request = findRequest(storeId, requestId);
        if (!isOwner(storeId, username) && !request.getRequester().getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("해당 매장의 관리자나 요청한 직원만 접근할 수 있습니다.");
        }
        return request;
    }

    private boolean isEligible(SwapRequest request, Long employeeId) {
        return substituteFinder.isEligible(request.getStore().getId(), request.getWorkDate(),
                request.getStartTime(), request.getEndTime(), employeeId, request.getRequester().getId());
    }

    private SwapRequest findActiveRequest(Long storeId, Long requestId) {
        SwapRequest request = findRequest(storeId, requestId);
        if (!request.isActive()) {
            throw new IllegalArgumentException("이미 처리된 결원 요청입니다.");
        }
        return request;
    }

    private SwapRequest findRequest(Long storeId, Long requestId) {
        SwapRequest request = swapRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 결원 요청입니다."));
        if (!request.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 결원 요청이 아닙니다.");
        }
        return request;
    }

    private static long minutes(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        return minutes <= 0 ? minutes + 24 * 60 : minutes;
    }
}
