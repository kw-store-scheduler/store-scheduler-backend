package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import com.example.store_scheduler_backend.service.HomeService;
import com.example.store_scheduler_backend.service.OpenShiftService;
import com.example.store_scheduler_backend.service.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

// 점주 홈 인건비·필요 인원, 동료 배정 사유 숨김, 필요 역량 알림, 스킬 해제
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OwnerHomeAndPrivacyTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired SkillRepository skillRepository;
    @Autowired EmployeeSkillLevelRepository levelRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired StaffingExceptionRepository exceptionRepository;
    @Autowired HomeService homeService;
    @Autowired OpenShiftService openShiftService;
    @Autowired SkillService skillService;

    private Store store;
    private Employee yuna;
    private Employee minho;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("홈", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "HOMECD01"));
        yuna = employee("yuna");
        minho = employee("minho");
    }

    @Test
    void 인건비는_급여와_같이_5인_미만이면_야간_가산이_없고_5인_이상이면_야간_시간만_가산한다() {
        schedule(yuna, 20, 0);
        store.updateOperations(null, false, null, null, null, null);
        assertThat(homeService.getOwnerHome(store.getId(), DAY, "owner").weeklyLaborCost()).isEqualTo(40_000);

        store.updateOperations(null, true, null, null, null, null);
        assertThat(homeService.getOwnerHome(store.getId(), DAY, "owner").weeklyLaborCost()).isEqualTo(40_000 + 10_000);
    }

    @Test
    void 필요_인원은_예외일_설정을_따르고_닫힌_교대는_빠진다() {
        Shift open = shift("오픈", 9, 13, 2);
        Shift closed = shift("마감", 17, 21, 1);
        exceptionRepository.save(new StaffingException(store, DAY, open, 3, 3, null));
        exceptionRepository.save(new StaffingException(store, DAY, closed, 0, 0, null));

        var shifts = homeService.getOwnerHome(store.getId(), DAY, "owner").todayShifts();

        assertThat(shifts).singleElement().satisfies(s -> {
            assertThat(s.shiftName()).isEqualTo("오픈");
            assertThat(s.requiredStaff()).isEqualTo(3);
        });
    }

    @Test
    void 직원은_동료의_배정_사유를_볼_수_없고_자기_것과_점주는_본다() throws Exception {
        Schedule mine = schedule(yuna, 9, 13);
        mine.setAssignmentReasons("가용 시간 09:00~13:00");
        Schedule theirs = schedule(minho, 14, 18);
        theirs.setAssignmentReasons("희망 휴무 신청일이지만 배정됨");

        String path = "$[?(@.id == %d)].reasons[0]";
        mockMvc.perform(get("/api/stores/{s}/schedules", store.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("yuna", "EMPLOYEE")))
                .andExpect(jsonPath(path, mine.getId()).exists())
                .andExpect(jsonPath(path, theirs.getId()).doesNotExist());
        mockMvc.perform(get("/api/stores/{s}/schedules", store.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER")))
                .andExpect(jsonPath(path, theirs.getId()).exists());
    }

    @Test
    void 추가_모집_알림은_필요_역량이_있는_직원에게만_간다() {
        Skill barista = skillRepository.save(new Skill("바리스타", store));
        yuna.addSkill(barista);

        openShiftService.createExtra(store.getId(), DAY, LocalTime.of(9, 0), LocalTime.of(13, 0), "단체 예약", null, 1,
                barista.getId(), null, "owner");

        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("yuna")).hasSize(1);
        assertThat(notificationRepository.findByUser_UsernameAndReadAtIsNull("minho")).isEmpty();
    }

    @Test
    void 스킬을_해제하면_등급도_지워진다() {
        Skill barista = skillRepository.save(new Skill("바리스타", store));
        yuna.addSkill(barista);
        EmployeeSkillLevel level = new EmployeeSkillLevel(yuna, barista);
        level.confirm(SkillLevel.HIGH);
        levelRepository.save(level);

        skillService.removeSkillFromEmployee(store.getId(), yuna.getId(), barista.getId(), "owner");
        levelRepository.flush();

        assertThat(levelRepository.findByEmployeeIdAndSkillId(yuna.getId(), barista.getId())).isEmpty();
    }

    private Employee employee(String name) {
        Employee e = new Employee(name, null, store, userRepository.save(new User(name, "pw", Role.EMPLOYEE)));
        e.approve(10000);
        employeeRepository.save(e);
        for (DayOfWeek d : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(e, d, LocalTime.MIN, LocalTime.of(23, 59)));
        }
        return e;
    }

    private Shift shift(String name, int start, int end, int staff) {
        Shift s = new Shift();
        s.setName(name);
        s.setStartTime(LocalTime.of(start, 0));
        s.setEndTime(LocalTime.of(end, 0));
        s.setRequiredStaff(staff);
        s.setStore(store);
        return shiftRepository.save(s);
    }

    private Schedule schedule(Employee employee, int start, int end) {
        Schedule s = new Schedule();
        s.setStore(store);
        s.setEmployee(employee);
        s.setWorkDate(DAY);
        s.setStartTime(LocalTime.of(start, 0));
        s.setEndTime(LocalTime.of(end, 0));
        return scheduleRepository.save(s);
    }
}
