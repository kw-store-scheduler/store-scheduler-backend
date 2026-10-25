package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DayOffRequestTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusWeeks(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;

    private Store store;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("A매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "DAYOFFAA"));
        storeRepository.save(new Store("B매장", null, userRepository.save(new User("otherOwner", "pw", Role.MANAGER)), "DAYOFFBB"));
        approved("staff");
        approved("coworker");
        employeeRepository.save(new Employee("대기", null, store, userRepository.save(new User("pending", "pw", Role.EMPLOYEE))));
    }

    @Test
    void 직원은_날짜별로_필수_희망_휴무를_신청하고_본인_목록을_본다() throws Exception {
        apply("staff", NEXT_WEEK, "MUST").andExpect(status().isOk()).andExpect(jsonPath("$.type").value("MUST"));
        apply("staff", NEXT_WEEK.plusDays(1), "PREFER").andExpect(status().isOk());

        as("staff", Role.EMPLOYEE, get("/api/stores/{storeId}/day-offs/my", store.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value(NEXT_WEEK.toString()));
    }

    @Test
    void 같은_날짜_중복_신청과_지난_날짜_신청은_거절한다() throws Exception {
        apply("staff", NEXT_WEEK, "MUST").andExpect(status().isOk());
        apply("staff", NEXT_WEEK, "PREFER").andExpect(status().isBadRequest());
        apply("staff", LocalDate.now().minusDays(1), "PREFER").andExpect(status().isBadRequest());
    }

    @Test
    void 승인되지_않은_직원은_신청할_수_없다() throws Exception {
        apply("pending", NEXT_WEEK, "MUST").andExpect(status().isForbidden());
    }

    @Test
    void 관리자는_기간별로_매장_휴무_신청을_보고_다른_매장_관리자는_볼_수_없다() throws Exception {
        apply("staff", NEXT_WEEK, "MUST");
        apply("coworker", NEXT_WEEK.plusDays(10), "PREFER");

        as("owner", Role.MANAGER, get("/api/stores/{storeId}/day-offs", store.getId())
                        .param("from", NEXT_WEEK.toString()).param("to", NEXT_WEEK.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeName").value("staff"));
        as("otherOwner", Role.MANAGER, get("/api/stores/{storeId}/day-offs", store.getId())
                        .param("from", NEXT_WEEK.toString()).param("to", NEXT_WEEK.plusDays(6).toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void 본인_신청만_취소할_수_있다() throws Exception {
        long id = objectMapper.readTree(apply("staff", NEXT_WEEK, "MUST").andReturn().getResponse().getContentAsString())
                .get("id").asLong();

        as("coworker", Role.EMPLOYEE, delete("/api/stores/{storeId}/day-offs/{id}", store.getId(), id))
                .andExpect(status().isForbidden());
        as("staff", Role.EMPLOYEE, delete("/api/stores/{storeId}/day-offs/{id}", store.getId(), id))
                .andExpect(status().isNoContent());
        assertThat(dayOffRequestRepository.findById(id)).isEmpty();
    }

    @Test
    void 필수_휴무는_한_달에_2일까지이고_넘으면_희망_휴무로_대안과_함께_받는다() throws Exception {
        java.time.YearMonth next = java.time.YearMonth.now().plusMonths(1);
        apply("staff", next.atDay(3), "MUST").andExpect(jsonPath("$.type").value("MUST"));
        apply("staff", next.atDay(4), "MUST").andExpect(jsonPath("$.downgraded").value(false));

        as("staff", Role.EMPLOYEE, post("/api/stores/{storeId}/day-offs", store.getId())
                .contentType("application/json")
                .content("{\"date\":\"" + next.atDay(5) + "\",\"type\":\"MUST\",\"fallbacks\":[\"AFTERNOON_OFF\",\"SWAP_DAY\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PREFER"))
                .andExpect(jsonPath("$.downgraded").value(true))
                .andExpect(jsonPath("$.fallbacks[0]").value("AFTERNOON_OFF"));
        // 다음 달 필수 휴무는 따로 센다
        apply("staff", next.plusMonths(1).atDay(3), "MUST").andExpect(jsonPath("$.type").value("MUST"));
    }

    private ResultActions apply(String username, LocalDate date, String type) throws Exception {
        return as(username, Role.EMPLOYEE, post("/api/stores/{storeId}/day-offs", store.getId())
                .contentType("application/json")
                .content("{\"date\":\"" + date + "\",\"type\":\"" + type + "\"}"));
    }

    private ResultActions as(String username, Role role, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, role.name())));
    }

    private void approved(String username) {
        Employee employee = new Employee(username, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.approve(10030);
        employeeRepository.save(employee);
    }
}
