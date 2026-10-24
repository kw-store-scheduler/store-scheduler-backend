package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * storeId만 바꿔서 다른 매장 데이터를 조회할 수 없는지 검증한다.
 * A 매장 관리자 / A 매장 승인 직원 / A 매장 승인 대기 직원 / B 매장 관리자 네 명을 기준으로 확인.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StoreReadAuthorizationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;

    private Long storeAId;

    @BeforeEach
    void setUp() {
        User ownerA = userRepository.save(new User("ownerA", "pw", Role.MANAGER));
        User ownerB = userRepository.save(new User("ownerB", "pw", Role.MANAGER));
        User staff = userRepository.save(new User("staffA", "pw", Role.EMPLOYEE));
        User pending = userRepository.save(new User("pendingA", "pw", Role.EMPLOYEE));

        Store storeA = storeRepository.save(new Store("A매장", null, ownerA, "CODEAAAA"));
        storeRepository.save(new Store("B매장", null, ownerB, "CODEBBBB"));
        storeAId = storeA.getId();

        Employee approved = new Employee("직원A", null, storeA, staff);
        approved.approve(10030);
        employeeRepository.save(approved);
        employeeRepository.save(new Employee("대기A", null, storeA, pending));

        Shift shift = new Shift();
        shift.setName("오픈");
        shift.setStartTime(LocalTime.of(9, 0));
        shift.setEndTime(LocalTime.of(13, 0));
        shift.setRequiredStaff(1);
        shift.setStore(storeA);
        shiftRepository.save(shift);
    }

    private ResultActions getAs(String username, Role role, String url) throws Exception {
        return mockMvc.perform(get(url, storeAId)
                .header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, role.name())));
    }

    @Test
    void 근무표는_매장_관리자와_승인된_직원만_조회할_수_있다() throws Exception {
        String url = "/api/stores/{storeId}/schedules";
        getAs("ownerA", Role.MANAGER, url).andExpect(status().isOk());
        getAs("staffA", Role.EMPLOYEE, url).andExpect(status().isOk());
        getAs("pendingA", Role.EMPLOYEE, url).andExpect(status().isForbidden());
        getAs("ownerB", Role.MANAGER, url).andExpect(status().isForbidden());
    }

    @Test
    void 근무_시간대는_매장_소속만_조회할_수_있다() throws Exception {
        String url = "/api/stores/{storeId}/shifts";
        getAs("staffA", Role.EMPLOYEE, url).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        getAs("ownerB", Role.MANAGER, url).andExpect(status().isForbidden());
    }

    @Test
    void 스킬_목록은_매장_소속만_조회할_수_있다() throws Exception {
        String url = "/api/stores/{storeId}/skills";
        getAs("staffA", Role.EMPLOYEE, url).andExpect(status().isOk());
        getAs("ownerB", Role.MANAGER, url).andExpect(status().isForbidden());
    }

    @Test
    void 직원_가용시간과_직원_목록은_해당_매장_관리자만_조회할_수_있다() throws Exception {
        for (String url : new String[]{
                "/api/stores/{storeId}/availabilities",
                "/api/stores/{storeId}/employees",
                "/api/stores/{storeId}/employees/pending"}) {
            getAs("ownerA", Role.MANAGER, url).andExpect(status().isOk());
            getAs("ownerB", Role.MANAGER, url).andExpect(status().isForbidden());
        }
    }

    @Test
    void 다른_매장_관리자는_스케줄_자동생성을_실행할_수_없다() throws Exception {
        mockMvc.perform(post("/api/stores/{storeId}/schedules/automate", storeAId)
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("ownerB", "MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 매장_목록은_본인_소유_매장만_반환한다() throws Exception {
        getAs("ownerA", Role.MANAGER, "/api/stores")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("A매장"));
    }
}
