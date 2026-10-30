package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.ProposalStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ScheduleAcknowledgement;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ScheduleProposal;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Assignment;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Candidate;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Shortage;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleProposalRepository;
import com.example.store_scheduler_backend.repository.ScheduleAcknowledgementRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ScheduleProposalService {

    private final ScheduleProposalRepository proposalRepository;
    private final ScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final FcmService fcmService;
    private final SwapRequestService swapRequestService;
    private final ShiftTradeService shiftTradeService;
    private final ObjectMapper objectMapper;
    private final ShiftRepository shiftRepository;
    private final StaffingPlanService staffingPlanService;
    private final ScheduleAcknowledgementRepository acknowledgementRepository;

    public ScheduleProposalResponse getProposal(Long storeId, Long proposalId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return toResponse(findProposal(storeId, proposalId));
    }

    // 선택한 후보안으로 해당 기간 근무표를 교체하고 직원들에게 알린다
    @Transactional
    public ScheduleProposalResponse confirm(Long storeId, Long proposalId, int candidateIndex, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        ScheduleProposal proposal = findProposal(storeId, proposalId);
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalArgumentException("이미 확정된 후보안입니다. 근무표를 다시 생성해주세요.");
        }

        List<Candidate> candidates = readCandidates(proposal);
        if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
            throw new IllegalArgumentException("존재하지 않는 후보안입니다: " + candidateIndex);
        }

        List<Employee> employees = employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED);
        Map<Long, Employee> employeeById = employees.stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));

        // 예외일에 교대 시간을 바꿨으면(마감 23시까지 등) 그날 근무 시각에 반영한다
        List<Shift> shifts = shiftRepository.findByStoreId(storeId);
        StaffingPlanService.Plan plan = staffingPlanService.planFor(storeId, proposal.getWeekStart(), proposal.getPeriodEnd());
        List<Schedule> schedules = new ArrayList<>();
        // 이미 지난 날짜는 실제 근무 기록이자 급여 근거라 후보안으로 덮어쓰지 않는다
        LocalDate today = LocalDate.now();
        for (Assignment a : candidates.get(candidateIndex).assignments()) {
            if (a.workDate().isBefore(today)) {
                continue;
            }
            Employee employee = employeeById.get(a.employeeId());
            if (employee == null) {
                // 후보안 생성 이후 퇴사·승인 취소된 직원이 있으면 그대로 반영할 수 없다
                throw new IllegalArgumentException("후보안 생성 이후 직원 정보가 바뀌었습니다. 근무표를 다시 생성해주세요.");
            }
            Schedule schedule = new Schedule();
            schedule.setStore(store);
            schedule.setEmployee(employee);
            schedule.setWorkDate(a.workDate());
            schedule.setStartTime(a.startTime());
            schedule.setEndTime(a.endTime());
            List<String> reasons = new ArrayList<>(a.reasons());
            shifts.stream()
                    .filter(sh -> sh.getStartTime().equals(a.startTime()) && sh.getEndTime().equals(a.endTime()))
                    .findFirst()
                    .map(sh -> plan.timeOverride(sh, a.workDate()))
                    .ifPresent(times -> {
                        schedule.setStartTime(times[0]);
                        schedule.setEndTime(times[1]);
                        reasons.add("예외일 시간 변경 " + times[0] + "~" + times[1]);
                    });
            schedule.setAssignmentReasons(reasons.isEmpty() ? null : String.join("\n", reasons));
            schedules.add(schedule);
        }

        List<Schedule> replaced = scheduleRepository.findByStoreIdAndWorkDateBetween(
                storeId, proposal.getWeekStart(), proposal.getPeriodEnd()).stream()
                .filter(s -> !s.getWorkDate().isBefore(today))
                .toList();
        swapRequestService.detachSchedules(replaced);
        shiftTradeService.detachSchedules(replaced.stream().map(Schedule::getId).toList());
        scheduleRepository.deleteAll(replaced);
        scheduleRepository.saveAll(schedules);
        proposal.confirm(candidateIndex);

        Candidate chosen = candidates.get(candidateIndex);
        String summary = chosen.metrics() != null && chosen.metrics().preferenceRate() != null
                ? " 희망 반영 " + chosen.metrics().preferenceRate() + "%." : "";
        for (Employee employee : employees) {
            acknowledgementRepository.save(new ScheduleAcknowledgement(proposal, employee));
            fcmService.send(employee.getUser(), "근무표가 확정됐어요",
                    proposal.getWeekStart() + "~" + proposal.getPeriodEnd() + " 근무표가 확정됐어요." + summary + " 배정 사유를 확인해 보세요.");
        }
        return toResponse(proposal);
    }

    // 점주: 확정 근무표를 누가 확인했는지
    public List<ScheduleAcknowledgement> findAcknowledgements(Long storeId, Long proposalId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        findProposal(storeId, proposalId);
        return acknowledgementRepository.findByProposalIdOrderByIdAsc(proposalId);
    }

    // 점주: 안 본 직원에게 다시 알림. 알린 인원 수를 돌려준다
    @Transactional
    public int remindUnseen(Long storeId, Long proposalId, String managerUsername) {
        List<ScheduleAcknowledgement> unseen = findAcknowledgements(storeId, proposalId, managerUsername).stream()
                .filter(a -> a.getSeenAt() == null && a.getEmployee().getStatus() == EmployeeStatus.APPROVED)
                .toList();
        for (ScheduleAcknowledgement a : unseen) {
            a.remind();
            fcmService.send(a.getEmployee().getUser(), "근무표를 확인해주세요",
                    a.getProposal().getWeekStart() + "~" + a.getProposal().getPeriodEnd() + " 확정 근무표를 아직 안 보셨어요.");
        }
        return unseen.size();
    }

    // 직원이 내 근무표를 열면 확인한 것으로 본다
    @Transactional
    public void acknowledge(Long storeId, String username) {
        employeeRepository.findByStoreIdAndUser_Username(storeId, username)
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
                .ifPresent(e -> acknowledgementRepository.findByEmployeeIdAndSeenAtIsNull(e.getId())
                        .forEach(ScheduleAcknowledgement::see));
    }

    private ScheduleProposal findProposal(Long storeId, Long proposalId) {
        ScheduleProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무표 후보안입니다."));
        if (!proposal.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 근무표 후보안이 아닙니다.");
        }
        return proposal;
    }

    private ScheduleProposalResponse toResponse(ScheduleProposal proposal) {
        List<Candidate> candidates = readCandidates(proposal);
        List<Shortage> shortages = objectMapper.readValue(proposal.getShortagesJson(), new TypeReference<>() {});
        return new ScheduleProposalResponse(proposal.getId(), true, proposal.getEngineStatus(),
                ScheduleProposalResponse.messageFor(candidates.size(), shortages.size()),
                proposal.getWeekStart(), proposal.getSolveMs(), proposal.getStatus(), proposal.getConfirmedIndex(),
                shortages, candidates, ScheduleProposalResponse.noticeFor(candidates, shortages), proposal.getPeriodEnd());
    }

    private List<Candidate> readCandidates(ScheduleProposal proposal) {
        return objectMapper.readValue(proposal.getCandidatesJson(), new TypeReference<>() {});
    }
}
