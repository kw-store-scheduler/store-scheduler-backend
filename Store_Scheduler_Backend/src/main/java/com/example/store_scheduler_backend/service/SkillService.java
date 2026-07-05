package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SkillService {

    private final SkillRepository skillRepository;
    private final StoreRepository storeRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional
    public Skill createSkill(Long storeId, String name, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        if (skillRepository.existsByStoreIdAndName(storeId, name)) {
            throw new IllegalArgumentException("이미 등록된 스킬입니다.");
        }
        return skillRepository.save(new Skill(name, store));
    }

    public List<Skill> findByStore(Long storeId) {
        return skillRepository.findByStoreId(storeId);
    }

    @Transactional
    public void deleteSkill(Long storeId, Long skillId, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        Skill skill = findOne(skillId);
        if (!skill.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 스킬이 아닙니다.");
        }
        skillRepository.delete(skill);
    }

    @Transactional
    public void assignSkillToEmployee(Long storeId, Long employeeId, Long skillId, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

        Employee employee = findEmployee(employeeId, storeId);
        Skill skill = findOne(skillId);
        if (!skill.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 스킬이 아닙니다.");
        }
        employee.addSkill(skill);
    }

    @Transactional
    public void removeSkillFromEmployee(Long storeId, Long employeeId, Long skillId, String managerUsername) {
        Store store = findStore(storeId);
        verifyOwner(store, managerUsername);

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

    private Store findStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));
    }

    private void verifyOwner(Store store, String username) {
        if (!store.getOwner().getUsername().equals(username)) {
            throw new IllegalArgumentException("해당 매장의 관리자만 접근할 수 있습니다.");
        }
    }
}
