package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.ShiftTradeService;
import com.example.store_scheduler_backend.service.SubstituteFinder;
import com.example.store_scheduler_backend.service.SwapRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 교대와 시간이 다른 근무의 희망 제출 판단, 근무 바꾸기와 결원 요청이 같은 근무에 겹치지 않는 것
@SpringBootTest
@Transactional
class VacancyRulesTest {

    private static final LocalDate DAY = YearMonth.now().plusMonths(1).atDay(10);

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ShiftPreferenceRepository preferenceRepository;
    @Autowired SubstituteFinder finder;
    @Autowired ShiftTradeService tradeService;
    @Autowired SwapRequestService swapService;

    private Store store;
    private Employee me;
    private Employee peer;
    private Shift open;
    private Schedule mine;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("규칙매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "RULECD01"));
        me = employee("me");
        peer = employee("peer");
        open = shift(9, 14);
        shift(17, 22);
        mine = new Schedule();
        mine.setStore(store);
        mine.setEmployee(me);
        mine.setWorkDate(DAY);
        mine.setStartTime(LocalTime.of(9, 0));
        mine.setEndTime(LocalTime.of(14, 0));
        scheduleRepository.save(mine);
    }

    @Test
    void 희망_제출자도_고른_칸_안의_일부_시간_근무는_가능하다() {
        preferenceRepository.save(new ShiftPreference(peer, DAY, open, PreferenceLevel.AVAILABLE));

        assertThat(eligible(peer, 11, 14)).isTrue();
    }

    @Test
    void 희망_제출자가_고르지_않은_칸_안의_시간은_불가능하다() {
        preferenceRepository.save(new ShiftPreference(peer, DAY, open, PreferenceLevel.AVAILABLE));

        assertThat(eligible(peer, 18, 21)).isFalse();
    }

    @Test
    void 어떤_교대에도_포함되지_않는_시간은_희망_제출로_막지_않는다() {
        preferenceRepository.save(new ShiftPreference(peer, DAY, open, PreferenceLevel.AVAILABLE));

        assertThat(eligible(peer, 13, 16)).isTrue();
    }

    @Test
    void 자기_자신에게는_근무를_넘길_수_없다() {
        assertThatThrownBy(() -> tradeService.request(store.getId(), mine.getId(), TradeType.HANDOVER, me.getId(), null, false, "me"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 근무_바꾸기가_진행_중이면_결원_요청을_낼_수_없다() {
        tradeService.request(store.getId(), mine.getId(), TradeType.HANDOVER, peer.getId(), null, false, "me");

        assertThatThrownBy(() -> swapService.request(store.getId(), mine.getId(), "사정", "me"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("근무 바꾸기");
    }

    @Test
    void 요청_뒤_담당자가_바뀐_근무_바꾸기는_승인되지_않는다() {
        var trade = tradeService.request(store.getId(), mine.getId(), TradeType.HANDOVER, peer.getId(), null, false, "me");
        tradeService.respond(store.getId(), trade.getId(), true, "peer");
        mine.setEmployee(employee("third"));

        assertThatThrownBy(() -> tradeService.approve(store.getId(), trade.getId(), "owner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("담당자");
    }

    private boolean eligible(Employee employee, int start, int end) {
        return finder.find(store.getId(), DAY, LocalTime.of(start, 0), LocalTime.of(end, 0), me.getId()).stream()
                .filter(c -> c.employee().getId().equals(employee.getId()))
                .findFirst().orElseThrow().eligible();
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

    private Shift shift(int start, int end) {
        Shift s = new Shift();
        s.setName(start + "시");
        s.setStartTime(LocalTime.of(start, 0));
        s.setEndTime(LocalTime.of(end, 0));
        s.setRequiredStaff(1);
        s.setStore(store);
        return shiftRepository.save(s);
    }
}
