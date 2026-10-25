package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
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
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoticeAndLeaveTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;

    private Store store;
    private Shift open;
    private Employee fullTime;
    private Employee partTime;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("공지매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "NOTICE01"));
        open = new Shift();
        open.setName("오픈");
        open.setStartTime(LocalTime.of(9, 0));
        open.setEndTime(LocalTime.of(14, 0));
        open.setRequiredStaff(1);
        open.setStore(store);
        shiftRepository.save(open);

        // 입사 2년 2개월 → 15일
        fullTime = employee("manager1", "정직원", EmploymentType.FULL_TIME, TODAY.minusYears(2).minusMonths(2));
        partTime = employee("part1", "알바", EmploymentType.PART_TIME, TODAY.minusMonths(3));
        Schedule schedule = new Schedule();
        schedule.setStore(store);
        schedule.setEmployee(partTime);
        schedule.setWorkDate(TODAY);
        schedule.setStartTime(open.getStartTime());
        schedule.setEndTime(open.getEndTime());
        scheduleRepository.save(schedule);
    }

    @Test
    void 공지는_구성원_모두_보고_알바_홈에_최신_공지가_나온다() throws Exception {
        as("owner", post("/api/stores/{storeId}/notices", store.getId())
                .contentType("application/json").content("{\"title\":\"겨울 신메뉴 레시피\",\"content\":\"...\"}"))
                .andExpect(status().isOk());
        as("part1", post("/api/stores/{storeId}/notices", store.getId())
                .contentType("application/json").content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());

        as("part1", get("/api/stores/{storeId}/notices", store.getId()))
                .andExpect(jsonPath("$[0].title").value("겨울 신메뉴 레시피"));
        as("part1", get("/api/stores/{storeId}/home/worker", store.getId()))
                .andExpect(jsonPath("$.latestNotice.title").value("겨울 신메뉴 레시피"))
                .andExpect(jsonPath("$.leaveBalance").doesNotExist());
    }

    @Test
    void 담당자_없는_교대_업무는_그날_그_교대_근무자에게_보이고_체크할_수_있다() throws Exception {
        String body = as("owner", post("/api/stores/{storeId}/tasks", store.getId())
                        .contentType("application/json")
                        .content("{\"date\":\"" + TODAY + "\",\"title\":\"오픈 점검\",\"shiftId\":" + open.getId() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(body).get("id").asLong();
        as("owner", post("/api/stores/{storeId}/tasks", store.getId())
                .contentType("application/json")
                .content("{\"date\":\"" + TODAY + "\",\"title\":\"월말 정산\",\"assigneeId\":" + fullTime.getId() + "}"))
                .andExpect(status().isOk());

        as("part1", get("/api/stores/{storeId}/tasks/my", store.getId()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("오픈 점검"));
        as("manager1", get("/api/stores/{storeId}/tasks/my", store.getId()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("월말 정산"));

        as("manager1", patch("/api/stores/{storeId}/tasks/{id}", store.getId(), taskId)
                .contentType("application/json").content("{\"done\":true}"))
                .andExpect(status().isBadRequest());
        as("part1", patch("/api/stores/{storeId}/tasks/{id}", store.getId(), taskId)
                .contentType("application/json").content("{\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.doneByName").value("알바"));
    }

    @Test
    void 정규직은_연차를_신청하고_승인되면_필수_휴무로_등록된다() throws Exception {
        as("manager1", get("/api/stores/{storeId}/leaves/my", store.getId()))
                .andExpect(jsonPath("$.balance.entitled").value(15))
                .andExpect(jsonPath("$.balance.remaining").value(15));

        LocalDate day = TODAY.plusDays(10);
        String body = as("manager1", post("/api/stores/{storeId}/leaves", store.getId())
                        .contentType("application/json").content("{\"date\":\"" + day + "\",\"reason\":\"가족 여행\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long leaveId = objectMapper.readTree(body).get("id").asLong();
        as("manager1", post("/api/stores/{storeId}/leaves", store.getId())
                .contentType("application/json").content("{\"date\":\"" + day + "\"}"))
                .andExpect(status().isBadRequest());
        as("part1", post("/api/stores/{storeId}/leaves", store.getId())
                .contentType("application/json").content("{\"date\":\"" + day + "\"}"))
                .andExpect(status().isBadRequest());

        as("owner", get("/api/stores/{storeId}/home/owner", store.getId()))
                .andExpect(jsonPath("$.pendingLeaves").value(1));
        as("owner", get("/api/stores/{storeId}/leaves", store.getId()))
                .andExpect(jsonPath("$[0].hasSchedule").value(false));
        as("owner", post("/api/stores/{storeId}/leaves/{id}/approve", store.getId(), leaveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(dayOffRequestRepository.findByEmployeeIdAndDate(fullTime.getId(), day)).get()
                .satisfies(r -> assertThat(r.getType()).isEqualTo(DayOffType.MUST));
        as("manager1", get("/api/stores/{storeId}/home/worker", store.getId()))
                .andExpect(jsonPath("$.leaveBalance.remaining").value(14));
    }

    private Employee employee(String username, String name, EmploymentType type, LocalDate startDate) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.fillJoinProfile(null, startDate, false, type);
        employee.approve(10_000);
        return employeeRepository.save(employee);
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
