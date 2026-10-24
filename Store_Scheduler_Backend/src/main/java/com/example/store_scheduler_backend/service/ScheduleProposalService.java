package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.ProposalStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.ScheduleProposal;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Assignment;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Candidate;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Shortage;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleProposalRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper;

    public ScheduleProposalResponse getProposal(Long storeId, Long proposalId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return toResponse(findProposal(storeId, proposalId));
    }

    // 선택한 후보안으로 해당 주 근무표를 교체하고 직원들에게 알린다
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

        List<Schedule> schedules = new ArrayList<>();
        for (Assignment a : candidates.get(candidateIndex).assignments()) {
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
            schedules.add(schedule);
        }

        scheduleRepository.deleteAll(scheduleRepository.findByStoreIdAndWorkDateBetween(
                storeId, proposal.getWeekStart(), proposal.getWeekStart().plusDays(6)));
        scheduleRepository.saveAll(schedules);
        proposal.confirm(candidateIndex);

        for (Employee employee : employees) {
            fcmService.sendToDevice(
                    employee.getUser().getDeviceToken(),
                    "근무표 업데이트",
                    "새로운 근무표가 등록되었습니다. 확인해주세요."
            );
        }
        return toResponse(proposal);
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
                shortages, candidates);
    }

    private List<Candidate> readCandidates(ScheduleProposal proposal) {
        return objectMapper.readValue(proposal.getCandidatesJson(), new TypeReference<>() {});
    }
}
