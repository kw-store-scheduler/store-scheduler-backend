package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 합류 신청 취소, 매장 업종 변경, 점주의 직원 고정 일정 조회
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JoinCancelAndStoreEditTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;

    private Store store;
    private User yuna;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("합류매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "CANCEL01"));
        yuna = userRepository.save(new User("yuna", "pw", Role.EMPLOYEE));
    }

    @Test
    void 처음_낸_합류_신청을_취소하면_기록이_사라져_역할을_바꿀_수_있다() throws Exception {
        Employee pending = employeeRepository.save(new Employee("유나", null, store, yuna));
        as("owner", put("/api/stores/{s}/employees/{e}/fixed-schedules", store.getId(), pending.getId())
                .contentType("application/json")
                .content("[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"label\":\"수업\"}]"))
                .andExpect(status().isOk());

        as("yuna", delete("/api/stores/{s}/join", store.getId())).andExpect(status().isNoContent());

        employeeRepository.flush();
        assertThat(employeeRepository.findByStoreIdAndUser_Username(store.getId(), "yuna")).isEmpty();
        as("yuna", patch("/api/users/me/role").contentType("application/json").content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 일한_적_있는_직원의_재합류_신청을_취소하면_퇴사_상태로_돌아간다() throws Exception {
        Employee former = new Employee("유나", null, store, yuna);
        former.approve(10_320);
        former.resign(LocalDate.now().minusDays(30));
        former.reapply("유나", null);
        employeeRepository.save(former);

        as("yuna", delete("/api/stores/{s}/join", store.getId())).andExpect(status().isNoContent());

        assertThat(employeeRepository.findById(former.getId()).orElseThrow().getStatus()).isEqualTo(EmployeeStatus.RESIGNED);
    }

    @Test
    void 승인된_직원과_신청이_없는_사람은_취소할_수_없다() throws Exception {
        as("yuna", delete("/api/stores/{s}/join", store.getId())).andExpect(status().isBadRequest());

        Employee approved = new Employee("유나", null, store, yuna);
        approved.approve(10_320);
        employeeRepository.save(approved);
        as("yuna", delete("/api/stores/{s}/join", store.getId())).andExpect(status().isBadRequest());
        assertThat(employeeRepository.findById(approved.getId())).isPresent();
    }

    @Test
    void 업종은_주면_바뀌고_생략하면_그대로다() throws Exception {
        as("owner", patch("/api/stores/{s}", store.getId()).contentType("application/json")
                .content("{\"name\":\"합류매장\",\"industry\":\"CAFE\"}"))
                .andExpect(status().isOk());
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getIndustry()).isEqualTo(Industry.CAFE);

        as("owner", patch("/api/stores/{s}", store.getId()).contentType("application/json")
                .content("{\"name\":\"새 이름\"}"))
                .andExpect(status().isOk());
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getIndustry()).isEqualTo(Industry.CAFE);
    }

    @Test
    void 점주는_직원의_고정_일정을_기간까지_조회한다() throws Exception {
        Employee approved = new Employee("유나", null, store, yuna);
        approved.approve(10_320);
        employeeRepository.save(approved);
        as("owner", put("/api/stores/{s}/employees/{e}/fixed-schedules", store.getId(), approved.getId())
                .contentType("application/json")
                .content("[{\"dayOfWeek\":\"MONDAY\",\"startTime\":\"10:00\",\"endTime\":\"12:00\",\"label\":\"수업\","
                        + "\"category\":\"STUDY\",\"validUntil\":\"2026-12-20\"}]"))
                .andExpect(status().isOk());

        as("owner", get("/api/stores/{s}/employees/{e}/fixed-schedules", store.getId(), approved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("수업"))
                .andExpect(jsonPath("$[0].category").value("STUDY"))
                .andExpect(jsonPath("$[0].validUntil").value("2026-12-20"));
        as("yuna", get("/api/stores/{s}/employees/{e}/fixed-schedules", store.getId(), approved.getId()))
                .andExpect(status().isForbidden());
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
