package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.OpenShift;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.EmployeeSkillLevelRepository;
import com.example.store_scheduler_backend.repository.OpenShiftRepository;
import com.example.store_scheduler_backend.repository.SkillWindowRequirementRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SkillService {

    private final SkillRepository skillRepository;
    private final EmployeeRepository employeeRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final ShiftSkillRequirementRepository requirementRepository;
    private final EmployeeSkillLevelRepository levelRepository;
    private final SkillWindowRequirementRepository windowRepository;
    private final OpenShiftRepository openShiftRepository;

    @Transactional
    public Skill createSkill(Long storeId, String name, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        if (skillRepository.existsByStoreIdAndName(storeId, name)) {
            throw new IllegalArgumentException("이미 등록된 스킬입니다.");
        }
        return skillRepository.save(new Skill(name, store));
    }

    public List<Skill> findByStore(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return skillRepository.findByStoreId(storeId);
    }

    @Transactional
    public void deleteSkill(Long storeId, Long skillId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        Skill skill = findOne(skillId);
        if (!skill.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 스킬이 아닙니다.");
        }
        // 직원 보유 스킬·교대 필요 스킬로 참조 중이면 외래키 때문에 삭제되지 않으므로 먼저 정리
        employeeRepository.findBySkills_Id(skillId).forEach(e -> e.removeSkill(skill));
        requirementRepository.deleteAll(requirementRepository.findBySkillId(skillId));
        levelRepository.deleteAll(levelRepository.findBySkillId(skillId));
        windowRepository.deleteAll(windowRepository.findBySkillId(skillId));
        openShiftRepository.findByRequiredSkillId(skillId).forEach(OpenShift::clearRequiredSkill);
        skillRepository.delete(skill);
    }

    @Transactional
    public void assignSkillToEmployee(Long storeId, Long employeeId, Long skillId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findEmployee(employeeId, storeId);
        Skill skill = findOne(skillId);
        if (!skill.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 스킬이 아닙니다.");
        }
        employee.addSkill(skill);
    }

    @Transactional
    public void removeSkillFromEmployee(Long storeId, Long employeeId, Long skillId, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);

        Employee employee = findEmployee(employeeId, storeId);
        Skill skill = findOne(skillId);
        employee.removeSkill(skill);
    }

    private Skill findOne(Long skillId) {
        return skillRepository.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스킬입니다."));
    }

    private Employee findEmployee(Long employeeId, Long storeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 직원입니다."));
        if (!employee.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장 소속 직원이 아닙니다.");
        }
        return employee;
    }
}
