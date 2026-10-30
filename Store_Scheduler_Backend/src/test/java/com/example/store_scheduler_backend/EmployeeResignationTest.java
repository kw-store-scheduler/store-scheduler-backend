package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import com.example.store_scheduler_backend.service.EmployeeService;
import com.example.store_scheduler_backend.service.PayrollService;
import com.example.store_scheduler_backend.service.ShiftTradeService;
import com.example.store_scheduler_backend.service.SubstituteFinder;
import com.example.store_scheduler_backend.service.SwapRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 퇴사 처리: 이후 근무와 진행 중인 요청 정리, 지난 급여 유지, 재합류
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmployeeResignationTest {

    private static final LocalDate NEXT_WEEK = LocalDate.now().plusWeeks(1);

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired EmployeeService employeeService;
    @Autowired SwapRequestService swapRequestService;
    @Autowired ShiftTradeService shiftTradeService;
    @Autowired SubstituteFinder substituteFinder;
    @Autowired PayrollService payrollService;

    private Store store;
    private Employee leaver;
    private Employee peer;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("퇴사매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "RESIGN01"));
        leaver = employee("leaver");
        peer = employee("peer");
    }

    @Test
    void 퇴사하면_이후_근무만_지워지고_지난_근무는_남는다() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        schedule(leaver, yesterday);
        schedule(leaver, NEXT_WEEK);

        mockMvc.perform(post("/api/stores/{s}/employees/{e}/resign", store.getId(), leaver.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedShifts").value(1));

        assertThat(scheduleRepository.findByStoreIdAndEmployeeIdOrderByWorkDateAscStartTimeAsc(store.getId(), leaver.getId()))
                .singleElement().satisfies(s -> assertThat(s.getWorkDate()).isEqualTo(yesterday));
        assertThat(leaver.getStatus()).isEqualTo(EmployeeStatus.RESIGNED);
    }

    @Test
    void 퇴사한_직원은_대타_후보에서_빠지고_매장_기능을_쓸_수_없다() {
        employeeService.resign(store.getId(), leaver.getId(), null, "owner");

        assertThat(substituteFinder.find(store.getId(), NEXT_WEEK, LocalTime.of(9, 0), LocalTime.of(13, 0), null))
                .noneMatch(c -> c.employee().getId().equals(leaver.getId()));
        assertThatThrownBy(() -> swapRequestService.findMyOffers(store.getId(), "leaver"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 승인_대기_중인_대타가_퇴사하면_다시_대타를_찾는_상태가_된다() {
        Schedule shift = schedule(peer, NEXT_WEEK);
        SwapRequest request = swapRequestService.request(store.getId(), shift.getId(), "사정", "peer");
        swapRequestService.sendOffers(store.getId(), request.getId(), List.of(leaver.getId()), "peer");
        swapRequestService.accept(store.getId(), request.getId(), "leaver");
        assertThat(request.getStatus()).isEqualTo(SwapStatus.PENDING_APPROVAL);

        employeeService.resign(store.getId(), leaver.getId(), null, "owner");

        assertThat(request.getStatus()).isEqualTo(SwapStatus.REQUESTED);
        assertThat(request.getSubstitute()).isNull();
    }

    @Test
    void 대타_요청을_받은_직원이_퇴사해_남은_요청이_없으면_다시_대타를_찾는_상태가_된다() {
        Schedule shift = schedule(peer, NEXT_WEEK);
        SwapRequest request = swapRequestService.request(store.getId(), shift.getId(), "사정", "peer");
        swapRequestService.sendOffers(store.getId(), request.getId(), List.of(leaver.getId()), "peer");

        employeeService.resign(store.getId(), leaver.getId(), null, "owner");

        assertThat(request.getStatus()).isEqualTo(SwapStatus.REQUESTED);
    }

    @Test
    void 진행_중인_근무_바꾸기는_퇴사와_함께_닫힌다() {
        Schedule shift = schedule(peer, LocalDate.now().plusDays(5));
        ShiftTrade trade = shiftTradeService.request(store.getId(), shift.getId(), TradeType.HANDOVER, leaver.getId(), null, false, "peer");

        employeeService.resign(store.getId(), leaver.getId(), null, "owner");

        assertThat(trade.getStatus()).isEqualTo(TradeStatus.CANCELLED);
    }

    @Test
    void 그달_근무가_있는_퇴사자는_급여에_포함되고_없으면_빠진다() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        schedule(leaver, yesterday);
        employeeService.resign(store.getId(), leaver.getId(), yesterday, "owner");

        assertThat(payrollService.calculateMonthlyPayroll(store.getId(), YearMonth.from(yesterday), "owner"))
                .anyMatch(p -> p.getEmployeeId().equals(leaver.getId()));
        assertThat(payrollService.calculateMonthlyPayroll(store.getId(), YearMonth.from(yesterday).minusMonths(2), "owner"))
                .noneMatch(p -> p.getEmployeeId().equals(leaver.getId()));
    }

    @Test
    void 오늘_이후를_마지막_근무일로_정할_수_없다() {
        assertThatThrownBy(() -> employeeService.resign(store.getId(), leaver.getId(), LocalDate.now().plusDays(1), "owner"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 퇴사하거나_거절된_직원은_다시_합류_신청할_수_있고_같은_직원_기록을_쓴다() {
        employeeService.resign(store.getId(), leaver.getId(), null, "owner");

        Employee rejoined = employeeService.joinStore(store.getStoreCode(), "복귀", null, "leaver");

        assertThat(rejoined.getId()).isEqualTo(leaver.getId());
        assertThat(rejoined.getStatus()).isEqualTo(EmployeeStatus.PENDING);
        employeeService.approve(store.getId(), rejoined.getId(), 11000, null, "owner");
        assertThat(rejoined.getResignedOn()).isNull();
    }

    private Employee employee(String name) {
        Employee e = new Employee(name, null, store, userRepository.save(new User(name, "pw", Role.EMPLOYEE)));
        e.approve(10030);
        employeeRepository.save(e);
        for (DayOfWeek d : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(e, d, LocalTime.MIN, LocalTime.of(23, 59)));
        }
        return e;
    }

    private Schedule schedule(Employee employee, LocalDate date) {
        Schedule s = new Schedule();
        s.setStore(store);
        s.setEmployee(employee);
        s.setWorkDate(date);
        s.setStartTime(LocalTime.of(9, 0));
        s.setEndTime(LocalTime.of(13, 0));
        return scheduleRepository.save(s);
    }
}
