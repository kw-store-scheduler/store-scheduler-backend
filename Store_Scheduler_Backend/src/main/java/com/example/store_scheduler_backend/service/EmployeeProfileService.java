package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeChangeLog;
import com.example.store_scheduler_backend.domain.EmployeePermission;
import com.example.store_scheduler_backend.domain.EmployeeSkillLevel;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.SkillLevel;
import com.example.store_scheduler_backend.domain.SkillWindowRequirement;
import com.example.store_scheduler_backend.domain.StaffPosition;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeChangeLogRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.EmployeeSkillLevelRepository;
import com.example.store_scheduler_backend.repository.FixedScheduleRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.SkillWindowRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// 역량 등급 신청·확정, 혼자 맡길 수 있는 일, 수습 기간, 변경 이력, 시간대별 필요 역량
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmployeeProfileService {

    private final StoreAccessGuard storeAccessGuard;
    private final EmployeeRepository employeeRepository;
    private final SkillRepository skillRepository;
    private final EmployeeSkillLevelRepository levelRepository;
    private final EmployeeChangeLogRepository changeLogRepository;
    private final SkillWindowRequirementRepository windowRepository;
    private final FixedScheduleRepository fixedScheduleRepository;
    private final FcmService fcmService;

    // level이 null이면 등급 미지정(보유만), requestedLevel이 있으면 확인 대기
    public record SkillState(Skill skill, SkillLevel level, SkillLevel requestedLevel, EmployeeSkillLevel row) {
    }

    public record Profile(Employee employee, List<SkillState> skills, List<EmployeeChangeLog> history) {
    }

    public record LevelInput(Long skillId, SkillLevel level) {
    }

    public record WindowInput(String label, LocalTime startTime, LocalTime endTime, Long skillId, SkillLevel minLevel,
                              EmployeePermission permission, int requiredCount, boolean mandatory, List<DayOfWeek> days) {
    }

    // 직원: 내 역량 (매장 스킬 전부, 확정 등급과 신청 중인 등급)
    public Profile getMine(Long storeId, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        return new Profile(me, skillStates(me), List.of());
    }

    // 직원: 등급 신청. 매니저가 확정하기 전까지는 배치에 쓰이지 않는다
    @Transactional
    public Profile requestLevels(Long storeId, List<LevelInput> inputs, String username) {
        Employee me = storeAccessGuard.verifyApprovedEmployee(storeId, username);
        for (LevelInput input : inputs) {
            Skill skill = storeSkill(storeId, input.skillId());
            if (input.level() == null) {
                throw new IllegalArgumentException("등급을 골라주세요.");
            }
            EmployeeSkillLevel row = levelRepository.findByEmployeeIdAndSkillId(me.getId(), skill.getId())
                    .orElseGet(() -> new EmployeeSkillLevel(me, skill));
            if (input.level() == effectiveLevel(me, skill, row)) {
                row.keep();
            } else {
                row.request(input.level());
            }
            levelRepository.save(row);
        }
        if (inputs.stream().anyMatch(i -> i.level() != null)) {
            fcmService.send(me.getStore().getOwner(), "역량 확인 요청",
                    me.getDisplayName() + "님이 역량을 신청했어요. 확인해주세요.");
        }
        return new Profile(me, skillStates(me), List.of());
    }

    // 점주: 확인 대기 중인 신청 전부
    public List<EmployeeSkillLevel> findPendingRequests(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return levelRepository.findByEmployee_StoreId(storeId).stream()
                .filter(EmployeeSkillLevel::isPending)
                .sorted(Comparator.comparing(EmployeeSkillLevel::getRequestedAt))
                .toList();
    }

    // 점주: 등급 확정 (신청 승인이든 직접 지정이든)
    @Transactional
    public Profile setLevel(Long storeId, Long employeeId, Long skillId, SkillLevel level, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = storeEmployee(storeId, employeeId);
        Skill skill = storeSkill(storeId, skillId);
        EmployeeSkillLevel row = levelRepository.findByEmployeeIdAndSkillId(employeeId, skillId)
                .orElseGet(() -> new EmployeeSkillLevel(employee, skill));
        SkillLevel before = effectiveLevel(employee, skill, row);
        row.confirm(level);
        levelRepository.save(row);
        if (level == SkillLevel.NONE) {
            employee.removeSkill(skill);
        } else {
            employee.addSkill(skill);
        }
        log(employee, skill.getName() + " " + label(before) + " → " + label(level) + " 확정");
        return getProfile(storeId, employeeId, managerUsername);
    }

    // 점주: 신청을 받아들이지 않고 기존 등급 유지
    @Transactional
    public Profile keepLevel(Long storeId, Long employeeId, Long skillId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = storeEmployee(storeId, employeeId);
        EmployeeSkillLevel row = levelRepository.findByEmployeeIdAndSkillId(employeeId, skillId)
                .filter(EmployeeSkillLevel::isPending)
                .orElseThrow(() -> new IllegalArgumentException("확인 대기 중인 신청이 없습니다."));
        row.keep();
        log(employee, row.getSkill().getName() + " 신청은 " + label(effectiveLevel(employee, row.getSkill(), row)) + "(으)로 유지");
        return getProfile(storeId, employeeId, managerUsername);
    }

    public Profile getProfile(Long storeId, Long employeeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = storeEmployee(storeId, employeeId);
        return new Profile(employee, skillStates(employee), changeLogRepository.findByEmployeeIdOrderByCreatedAtDescIdDesc(employeeId));
    }

    // 점주: 혼자 맡길 수 있는 일, 수습 기간, 근로계약 주 시간. null인 항목은 바꾸지 않는다 (수습을 없애려면 clearProbation)
    @Transactional
    public Profile updateProfile(Long storeId, Long employeeId, List<EmployeePermission> permissions, LocalDate probationUntil,
                                 boolean clearProbation, Integer contractMin, Integer contractMax, StaffPosition position,
                                 String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = storeEmployee(storeId, employeeId);
        if (position != null && position != employee.getPosition()) {
            employee.updatePosition(position);
            log(employee, "직책 " + position.getLabel() + "(으)로 변경");
        }
        if (contractMin != null && contractMax != null) {
            if (contractMin < 0 || contractMax < contractMin || contractMax > 52) {
                throw new IllegalArgumentException("계약 주 근무 시간 범위를 확인해주세요.");
            }
            employee.updateContract(contractMin, contractMax);
            log(employee, "근로계약 주 " + contractMin + "–" + contractMax + "시간");
        }
        if (permissions != null) {
            List<EmployeePermission> before = employee.getPermissionList();
            employee.updatePermissions(permissions);
            for (EmployeePermission p : EmployeePermission.values()) {
                if (before.contains(p) != permissions.contains(p)) {
                    log(employee, p.getLabel() + (permissions.contains(p) ? " 허용" : " 해제"));
                }
            }
        }
        if (clearProbation) {
            employee.updateProbation(null);
            log(employee, "수습 기간 종료");
        } else if (probationUntil != null) {
            employee.updateProbation(probationUntil);
            log(employee, "수습 기간 " + probationUntil.getMonthValue() + "/" + probationUntil.getDayOfMonth() + "까지 설정");
        }
        return getProfile(storeId, employeeId, managerUsername);
    }

    public List<FixedSchedule> findFixedSchedules(Long storeId, Long employeeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return fixedScheduleRepository.findByEmployeeId(storeEmployee(storeId, employeeId).getId());
    }

    // 점주: 직원 대신 고정 일정 입력
    @Transactional
    public List<FixedSchedule> replaceFixedSchedules(Long storeId, Long employeeId, List<PreferenceService.FixedScheduleInput> inputs,
                                                     String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Employee employee = storeEmployee(storeId, employeeId);
        List<FixedSchedule> rows = new ArrayList<>();
        for (PreferenceService.FixedScheduleInput input : inputs) {
            if (input.dayOfWeek() == null || input.startTime() == null || input.endTime() == null
                    || !input.endTime().isAfter(input.startTime())) {
                throw new IllegalArgumentException("고정 일정의 요일과 시작·종료 시간을 확인해주세요.");
            }
            rows.add(new FixedSchedule(employee, input.dayOfWeek(), input.startTime(), input.endTime(),
                    input.label() != null && !input.label().isBlank() ? input.label() : "고정 일정",
                    input.category(), input.validFrom(), input.validUntil()));
        }
        fixedScheduleRepository.deleteAll(fixedScheduleRepository.findByEmployeeId(employeeId));
        fixedScheduleRepository.flush();
        List<FixedSchedule> saved = fixedScheduleRepository.saveAll(rows);
        log(employee, "사장님이 고정 일정 " + saved.size() + "건 입력");
        return saved;
    }

    // 점주: 직원 × 스킬 등급 표 (O04 Q1)
    public Map<Employee, List<SkillState>> getMatrix(Long storeId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return employeeRepository.findByStoreIdAndStatus(storeId, EmployeeStatus.APPROVED).stream()
                .collect(Collectors.toMap(Function.identity(), this::skillStates, (a, b) -> a, LinkedHashMap::new));
    }

    public List<SkillWindowRequirement> findWindows(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return windowRepository.findByStoreIdOrderByStartTimeAsc(storeId);
    }

    @Transactional
    public SkillWindowRequirement addWindow(Long storeId, WindowInput input, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        if (input.startTime() == null || input.endTime() == null || !input.endTime().isAfter(input.startTime())) {
            throw new IllegalArgumentException("시간대의 시작·종료 시간을 확인해주세요.");
        }
        if ((input.skillId() == null) == (input.permission() == null)) {
            throw new IllegalArgumentException("역량(skillId)과 맡길 수 있는 일(permission) 중 하나만 골라주세요.");
        }
        if (input.requiredCount() < 1) {
            throw new IllegalArgumentException("필요 인원은 1명 이상이어야 합니다.");
        }
        Skill skill = input.skillId() != null ? storeSkill(storeId, input.skillId()) : null;
        return windowRepository.save(new SkillWindowRequirement(store, input.label(), input.startTime(), input.endTime(), skill,
                skill != null && input.minLevel() != SkillLevel.NONE ? input.minLevel() : null, input.permission(),
                input.requiredCount(), input.mandatory(), input.days()));
    }

    @Transactional
    public void deleteWindow(Long storeId, Long windowId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        windowRepository.delete(windowRepository.findById(windowId)
                .filter(w -> w.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 시간대 역량입니다.")));
    }

    // 기존 데이터처럼 등급 행 없이 보유 스킬에만 있으면 등급 미지정(null)
    public static SkillLevel effectiveLevel(Employee employee, Skill skill, EmployeeSkillLevel row) {
        if (row != null && row.getLevel() != null) {
            return row.getLevel();
        }
        return employee.getSkills().stream().anyMatch(s -> s.getId().equals(skill.getId())) ? null : SkillLevel.NONE;
    }

    private List<SkillState> skillStates(Employee employee) {
        Map<Long, EmployeeSkillLevel> rows = levelRepository.findByEmployeeId(employee.getId()).stream()
                .collect(Collectors.toMap(r -> r.getSkill().getId(), Function.identity()));
        return skillRepository.findByStoreId(employee.getStore().getId()).stream()
                .sorted(Comparator.comparing(Skill::getId))
                .map(skill -> {
                    EmployeeSkillLevel row = rows.get(skill.getId());
                    return new SkillState(skill, effectiveLevel(employee, skill, row),
                            row != null ? row.getRequestedLevel() : null, row);
                })
                .toList();
    }

    // 다른 서비스에서 바꾼 항목(시급, 고용 형태)도 같은 이력에 남긴다
    @Transactional
    public void recordChange(Employee employee, String description) {
        log(employee, description);
    }

    private void log(Employee employee, String description) {
        changeLogRepository.save(new EmployeeChangeLog(employee, description));
        fcmService.send(employee.getUser(), "내 정보 변경", description);
    }

    private static String label(SkillLevel level) {
        if (level == null) {
            return "보유";
        }
        return switch (level) {
            case NONE -> "없음";
            case LOW -> "하";
            case MID -> "중";
            case HIGH -> "상";
        };
    }

    private Employee storeEmployee(Long storeId, Long employeeId) {
        return employeeRepository.findById(employeeId)
                .filter(e -> e.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장 소속 직원이 아닙니다."));
    }

    private Skill storeSkill(Long storeId, Long skillId) {
        return Optional.ofNullable(skillId).flatMap(skillRepository::findById)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 스킬이 아닙니다: " + skillId));
    }
}
