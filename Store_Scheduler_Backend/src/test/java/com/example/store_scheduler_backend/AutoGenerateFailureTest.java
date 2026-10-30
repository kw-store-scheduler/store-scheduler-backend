package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.ScheduleEngine;
import com.example.store_scheduler_backend.service.SchedulePlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// 실제 스케줄 작업처럼 바깥 트랜잭션 없이 실행해야 롤백 전파를 확인할 수 있어 @Transactional을 붙이지 않는다
@SpringBootTest
class AutoGenerateFailureTest {

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired SchedulePlanService schedulePlanService;
    @MockitoBean ScheduleEngine engine;

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll();
        shiftRepository.deleteAll();
        employeeRepository.deleteAll();
        storeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 한_매장이_실패해도_다른_매장은_처리되고_실패_알림은_한_번만_간다() {
        Map<String, Object> noSolution = new HashMap<>();
        noSolution.put("status", "INFEASIBLE");
        noSolution.put("schedule", null);
        when(engine.run(any())).thenReturn(noSolution);
        autoStore("AUTOFAL1", false);
        autoStore("AUTOFAL2", true);

        assertThat(schedulePlanService.generateDue(LocalDateTime.now())).isZero();
        assertThat(schedulePlanService.generateDue(LocalDateTime.now())).isZero();

        List<String> titles = notificationRepository.findAll().stream().map(Notification::getTitle).toList();
        assertThat(titles).containsExactly("근무표 자동 생성 실패", "근무표 자동 생성 실패");
    }

    @Test
    void 엔진_오류가_나도_실패_알림을_남기고_넘어간다() {
        when(engine.run(any())).thenThrow(new IllegalStateException("엔진 연결 실패"));
        autoStore("AUTOFAL3", true);

        assertThat(schedulePlanService.generateDue(LocalDateTime.now())).isZero();

        assertThat(notificationRepository.findAll()).singleElement()
                .satisfies(n -> assertThat(n.getBody()).doesNotContain("엔진"));
    }

    private void autoStore(String code, boolean staffed) {
        Store store = new Store("자동", null, userRepository.save(new User("o" + code, "pw", Role.MANAGER)), code);
        store.updateOperations(null, null, ScheduleCycle.WEEKLY, true, 14, 0);
        storeRepository.save(store);
        if (staffed) {
            Employee employee = new Employee("직원", null, store, userRepository.save(new User("e" + code, "pw", Role.EMPLOYEE)));
            employee.approve(10030);
            employeeRepository.save(employee);
            Shift shift = new Shift();
            shift.setName("오픈");
            shift.setStartTime(LocalTime.of(9, 0));
            shift.setEndTime(LocalTime.of(13, 0));
            shift.setRequiredStaff(1);
            shift.setStore(store);
            shiftRepository.save(shift);
        }
    }
}
