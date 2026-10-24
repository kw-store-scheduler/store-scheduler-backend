package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShiftSkillRequirementTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired SkillRepository skillRepository;
    @Autowired ShiftSkillRequirementRepository requirementRepository;

    private Store store;
    private Shift shift;
    private Skill japanese;
    private Skill otherStoreSkill;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("A매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "SKILLAAA"));
        Store other = storeRepository.save(new Store("B매장", null, userRepository.save(new User("otherOwner", "pw", Role.MANAGER)), "SKILLBBB"));

        shift = new Shift();
        shift.setName("오픈");
        shift.setStartTime(LocalTime.of(9, 0));
        shift.setEndTime(LocalTime.of(13, 0));
        shift.setRequiredStaff(2);
        shift.setStore(store);
        shiftRepository.save(shift);

        japanese = skillRepository.save(new Skill("일본어", store));
        otherStoreSkill = skillRepository.save(new Skill("영어", other));
    }

    @Test
    void 필요_스킬을_설정하면_근무_시간대_조회에_함께_나온다() throws Exception {
        replace("owner", "[{\"skillId\":" + japanese.getId() + ",\"count\":1}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillName").value("일본어"));

        as("owner", Role.MANAGER, get("/api/stores/{storeId}/shifts", store.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skillRequirements[0].skillName").value("일본어"))
                .andExpect(jsonPath("$[0].skillRequirements[0].count").value(1));
    }

    @Test
    void 다시_설정하면_기존_설정을_교체하고_빈_목록이면_해제한다() throws Exception {
        replace("owner", "[{\"skillId\":" + japanese.getId() + ",\"count\":1}]").andExpect(status().isOk());
        replace("owner", "[{\"skillId\":" + japanese.getId() + ",\"count\":2}]").andExpect(status().isOk());
        assertThat(requirementRepository.findByShiftId(shift.getId()))
                .singleElement().extracting(ShiftSkillRequirement::getRequiredCount).isEqualTo(2);

        replace("owner", "[]").andExpect(status().isOk());
        assertThat(requirementRepository.findByShiftId(shift.getId())).isEmpty();
    }

    @Test
    void 잘못된_입력은_거절한다() throws Exception {
        replace("owner", "[{\"skillId\":" + otherStoreSkill.getId() + ",\"count\":1}]").andExpect(status().isBadRequest());
        replace("owner", "[{\"skillId\":" + japanese.getId() + ",\"count\":0}]").andExpect(status().isBadRequest());
        replace("owner", "[{\"skillId\":" + japanese.getId() + ",\"count\":3}]").andExpect(status().isBadRequest());
        replace("owner", "[{\"skillId\":" + japanese.getId() + ",\"count\":1},{\"skillId\":" + japanese.getId() + ",\"count\":1}]")
                .andExpect(status().isBadRequest());
    }

    @Test
    void 다른_매장_관리자는_설정할_수_없다() throws Exception {
        replace("otherOwner", "[{\"skillId\":" + japanese.getId() + ",\"count\":1}]").andExpect(status().isForbidden());
    }

    @Test
    void 직원과_교대가_사용_중인_스킬도_삭제할_수_있다() throws Exception {
        Employee employee = new Employee("직원", null, store, userRepository.save(new User("staff", "pw", Role.EMPLOYEE)));
        employee.approve(10030);
        employee.addSkill(japanese);
        employeeRepository.save(employee);
        requirementRepository.save(new ShiftSkillRequirement(shift, japanese, 1));
        employeeRepository.flush();

        as("owner", Role.MANAGER, delete("/api/stores/{storeId}/skills/{skillId}", store.getId(), japanese.getId()))
                .andExpect(status().isOk());
        skillRepository.flush();

        assertThat(skillRepository.findById(japanese.getId())).isEmpty();
        assertThat(requirementRepository.findByShiftId(shift.getId())).isEmpty();
        assertThat(employeeRepository.findById(employee.getId()).orElseThrow().getSkills()).isEmpty();
    }

    private ResultActions replace(String username, String json) throws Exception {
        return as(username, Role.MANAGER, put("/api/stores/{storeId}/shifts/{shiftId}/skill-requirements", store.getId(), shift.getId())
                .contentType("application/json").content(json));
    }

    private ResultActions as(String username, Role role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, role.name())));
    }
}
