package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final StoreAccessGuard storeAccessGuard;
    private final SkillRepository skillRepository;
    private final ShiftSkillRequirementRepository requirementRepository;

    @Transactional
    public Shift registerShift(Long storeId, String name, LocalTime startTime, LocalTime endTime,
                               int requiredStaff, DayOfWeek dayOfWeek, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        shift.setRequiredStaff(requiredStaff);
        shift.setDayOfWeek(dayOfWeek);
        shift.setStore(store);
        return shiftRepository.save(shift);
    }

    public List<Shift> findByStore(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return shiftRepository.findByStoreId(storeId);
    }

    public Map<Long, List<ShiftSkillRequirement>> findSkillRequirementsByStore(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return requirementRepository.findByShift_Store_Id(storeId).stream()
                .collect(Collectors.groupingBy(r -> r.getShift().getId()));
    }

    // 교대의 필요 스킬을 요청 내용으로 통째로 교체한다 (빈 목록이면 모두 해제)
    @Transactional
    public List<ShiftSkillRequirement> replaceSkillRequirements(Long storeId, Long shiftId, Map<Long, Integer> countBySkillId,
                                                                String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Shift shift = shiftRepository.findById(shiftId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 근무 시간대입니다."));

        List<ShiftSkillRequirement> requirements = new ArrayList<>();
        countBySkillId.forEach((skillId, count) -> {
            Skill skill = skillRepository.findById(skillId)
                    .filter(s -> s.getStore().getId().equals(storeId))
                    .orElseThrow(() -> new IllegalArgumentException("해당 매장의 스킬이 아닙니다: " + skillId));
            if (count > shift.getRequiredStaff()) {
                throw new IllegalArgumentException("필요 스킬 인원은 근무 필요 인원(" + shift.getRequiredStaff() + "명)을 넘을 수 없습니다.");
            }
            requirements.add(new ShiftSkillRequirement(shift, skill, count));
        });

        requirementRepository.deleteAll(requirementRepository.findByShiftId(shiftId));
        requirementRepository.flush();
        return requirementRepository.saveAll(requirements);
    }
}
