package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import com.example.store_scheduler_backend.service.AuthService;
import com.example.store_scheduler_backend.service.LeaveService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountAndLeaveCancelTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AuthService authService;
    @Autowired LeaveService leaveService;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;

    @Test
    void 비밀번호를_바꾸면_새_비밀번호로만_로그인되고_리프레시_토큰이_끊긴다() {
        authService.signup("acct1", "password1", Role.MANAGER);
        String refresh = authService.login("acct1", "password1").refreshToken();

        assertThatThrownBy(() -> authService.changePassword("acct1", "wrong-password", "password2"))
                .isInstanceOf(IllegalArgumentException.class);
        authService.changePassword("acct1", "password1", "password2");

        assertThat(authService.login("acct1", "password2").token()).isNotBlank();
        assertThatThrownBy(() -> authService.refresh(refresh)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 리프레시_토큰은_한_번만_쓸_수_있다() {
        authService.signup("acct2", "password1", Role.EMPLOYEE);
        String refresh = authService.login("acct2", "password1").refreshToken();

        AuthService.Tokens renewed = authService.refresh(refresh);

        assertThat(renewed.token()).isNotBlank();
        assertThat(renewed.refreshToken()).isNotEqualTo(refresh);
        assertThatThrownBy(() -> authService.refresh(refresh)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void 로그인을_다섯_번_틀리면_맞는_비밀번호도_잠시_막힌다() throws Exception {
        authService.signup("acct3", "password1", Role.EMPLOYEE);
        String wrong = "{\"username\":\"acct3\",\"password\":\"wrong\"}";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").contentType("application/json").content(wrong)).andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"acct3\",\"password\":\"password1\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void 짧은_비밀번호로는_가입할_수_없다() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType("application/json")
                        .content("{\"username\":\"acct4\",\"password\":\"short\",\"role\":\"EMPLOYEE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 점주가_매장_이름과_주소를_고친다() throws Exception {
        Store store = storeRepository.save(new Store("옛이름", null, userRepository.save(new User("own5", "pw", Role.MANAGER)), "INFOCD01"));

        mockMvc.perform(patch("/api/stores/{id}", store.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("own5", "MANAGER"))
                        .contentType("application/json").content("{\"name\":\"새이름\",\"address\":\"서울\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름"))
                .andExpect(jsonPath("$.address").value("서울"));
    }

    @Test
    void 승인된_연차를_취소하면_필수_휴무가_지워지고_같은_날_다시_신청할_수_있다() {
        Store store = storeRepository.save(new Store("연차", null, userRepository.save(new User("own6", "pw", Role.MANAGER)), "LEAVECD1"));
        Employee employee = new Employee("정규", null, store, userRepository.save(new User("emp6", "pw", Role.EMPLOYEE)));
        employee.fillJoinProfile(null, LocalDate.now().minusYears(2), false, EmploymentType.FULL_TIME);
        employee.approve(12000);
        employeeRepository.save(employee);
        LocalDate day = LocalDate.now().plusDays(5);

        LeaveRequest leave = leaveService.request(store.getId(), day, "여행", "emp6");
        leaveService.approve(store.getId(), leave.getId(), "own6");
        assertThat(dayOffRequestRepository.existsByEmployeeIdAndDate(employee.getId(), day)).isTrue();

        leaveService.cancel(store.getId(), leave.getId(), "emp6");
        dayOffRequestRepository.flush();

        assertThat(leave.getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        assertThat(dayOffRequestRepository.existsByEmployeeIdAndDate(employee.getId(), day)).isFalse();
        assertThat(leaveService.request(store.getId(), day, "다시", "emp6").getStatus()).isEqualTo(LeaveStatus.PENDING);
    }
}
