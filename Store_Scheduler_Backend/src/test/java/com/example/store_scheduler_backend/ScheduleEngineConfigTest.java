package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse;
import com.example.store_scheduler_backend.dto.ScheduleProposalResponse.Candidate;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.service.PythonEngineRunner;
import com.example.store_scheduler_backend.service.ScheduleAutomationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 엔진에 넘기는 config가 매장 데이터를 정확히 반영하는지 검증한다.
 * 엔진 결과는 실행마다 달라질 수 있어, 엔진은 고정된 응답을 돌려주는 mock으로 대체한다.
 *
 * 교대 인덱스(시작 시간순): 0=오픈 09~13, 1=토요특근 13~17(토요일 전용), 2=마감 22~02
 */
@SpringBootTest
@Transactional
class ScheduleEngineConfigTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 2); // 월요일
    private static final int MON = 0;
    private static final int SAT = 5;
    private static final int OPEN = 0;
    private static final int SAT_ONLY = 1;
    private static final int CLOSE = 2;

    @MockitoBean PythonEngineRunner pythonEngineRunner;

    @Autowired ScheduleAutomationService automationService;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SkillRepository skillRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;
    @Autowired ShiftSkillRequirementRepository requirementRepository;

    private Store store;
    private Employee employee;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(new User("owner", "pw", Role.MANAGER));
        store = storeRepository.save(new Store("테스트매장", null, owner, "CFGCODE1"));

        employee = new Employee("오전형", null, store, userRepository.save(new User("staff", "pw", Role.EMPLOYEE)));
        employee.approve(10030);
        employeeRepository.save(employee);
        availabilityRepository.save(new Availability(employee, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)));
        availabilityRepository.save(new Availability(employee, DayOfWeek.SATURDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)));

        shift("마감", LocalTime.of(22, 0), LocalTime.of(2, 0), null);
        shift("오픈", LocalTime.of(9, 0), LocalTime.of(13, 0), null);
        shift("토요특근", LocalTime.of(13, 0), LocalTime.of(17, 0), DayOfWeek.SATURDAY);

        when(pythonEngineRunner.run(any())).thenReturn(Map.of(
                "status", "OPTIMAL",
                "schedule", List.of(),
                "cost", 0,
                "solve_ms", 1,
                "shortage_report", List.of(
                        Map.of("day", MON, "shift", SAT_ONLY, "required", 1, "actual", 0, "shortage", 1),
                        Map.of("day", SAT, "shift", SAT_ONLY, "required", 1, "actual", 0, "shortage", 1))));
    }

    @Test
    void 가용_시간_밖의_교대와_해당_요일_전용이_아닌_교대를_근무_불가로_넘긴다() {
        Map<String, Object> employeeConfig = (Map<String, Object>) ((List<?>) generateAndCaptureConfig().get("employees")).get(0);

        assertThat((List<Integer>) employeeConfig.get("available_days")).containsExactlyInAnyOrder(MON, SAT);
        assertThat((List<Map<String, Integer>>) employeeConfig.get("unavailable_slots")).containsExactlyInAnyOrder(
                slot(MON, SAT_ONLY),  // 토요일 전용 교대
                slot(MON, CLOSE),     // 월요일은 13시까지라 마감 불가
                slot(SAT, CLOSE));    // 토요일도 18시까지라 마감 불가 (토요특근 13~17은 가능)
    }

    @Test
    void 같은_주에_기존_근무표가_있으면_변경_최소화를_위해_엔진에_넘긴다() {
        Schedule existing = new Schedule();
        existing.setStore(store);
        existing.setEmployee(employee);
        existing.setWorkDate(WEEK_START);
        existing.setStartTime(LocalTime.of(9, 0));
        existing.setEndTime(LocalTime.of(13, 0));
        scheduleRepository.save(existing);

        Map<String, Object> config = generateAndCaptureConfig();

        assertThat((List<Map<String, Object>>) config.get("prev_assignments")).containsExactly(
                Map.of("employee_id", "emp_" + employee.getId(), "day", MON, "shift", OPEN));
        // 후보안 생성만으로는 기존 근무표를 건드리지 않는다
        assertThat(scheduleRepository.findByStoreIdAndWorkDateBetween(store.getId(), WEEK_START, WEEK_START.plusDays(6))).hasSize(1);
    }

    @Test
    void 요일_전용_교대는_해당_요일이_아니면_인원_부족으로_보고하지_않는다() {
        ScheduleProposalResponse response = automationService.generateProposal(store.getId(), WEEK_START, "owner");

        assertThat(response.getShortages()).hasSize(1);
        assertThat(response.getShortages().get(0).date()).isEqualTo(WEEK_START.plusDays(SAT));
        assertThat(response.getShortages().get(0).shiftName()).isEqualTo("토요특근");
    }

    @Test
    void 대안_해가_있으면_후보안마다_직원별_시급으로_비용과_변경_칸_수를_계산한다() {
        Employee senior = new Employee("시니어", null, store, userRepository.save(new User("senior", "pw", Role.EMPLOYEE)));
        senior.approve(12000);
        employeeRepository.save(senior);
        Schedule existing = new Schedule();
        existing.setStore(store);
        existing.setEmployee(employee);
        existing.setWorkDate(WEEK_START);
        existing.setStartTime(LocalTime.of(9, 0));
        existing.setEndTime(LocalTime.of(13, 0));
        scheduleRepository.save(existing);

        // 0번: 기존과 같이 오전형이 월 오픈 / 1번: 시니어가 월 오픈, 2번: 시니어가 토 마감(야간)
        when(pythonEngineRunner.run(any())).thenReturn(Map.of(
                "status", "OPTIMAL", "cost", 0, "solve_ms", 1, "shortage_report", List.of(),
                "schedule", List.of(assigned(employee, MON, OPEN), assigned(senior)),
                "alt_schedules", List.of(
                        List.of(assigned(employee), assigned(senior, MON, OPEN)),
                        List.of(assigned(employee), assigned(senior, SAT, CLOSE)))));

        List<Candidate> candidates = automationService.generateProposal(store.getId(), WEEK_START, "owner").getCandidates();

        assertThat(candidates).hasSize(3);
        assertThat(candidates).extracting(Candidate::estimatedCost).containsExactly(
                4L * 10030, 4L * 12000, Math.round(4L * 12000 * 1.5));
        assertThat(candidates).extracting(Candidate::changedFromCurrent).containsExactly(0, 2, 2);
        assertThat(candidates.get(1).employees()).filteredOn(e -> e.name().equals("시니어"))
                .singleElement().satisfies(e -> {
                    assertThat(e.workDays()).isEqualTo(1);
                    assertThat(e.hours()).isEqualTo(4);
                });
    }

    @Test
    void 직원_스킬과_교대_필요_스킬을_id로_넘기고_역량_부족은_스킬_이름으로_돌려준다() {
        Skill japanese = skillRepository.save(new Skill("일본어", store));
        employee.addSkill(japanese);
        Shift open = shiftRepository.findByStoreId(store.getId()).stream()
                .filter(s -> s.getName().equals("오픈")).findFirst().orElseThrow();
        requirementRepository.save(new ShiftSkillRequirement(open, japanese, 1));
        String skillKey = "skill_" + japanese.getId();

        when(pythonEngineRunner.run(any())).thenReturn(Map.of(
                "status", "OPTIMAL", "schedule", List.of(), "cost", 0, "solve_ms", 1, "shortage_report", List.of(),
                "skill_shortage_report", List.of(
                        Map.of("day", MON, "shift", OPEN, "skill", skillKey, "required", 1, "actual", 0))));

        ScheduleProposalResponse response = automationService.generateProposal(store.getId(), WEEK_START, "owner");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pythonEngineRunner).run(captor.capture());
        Map<String, Object> config = captor.getValue();
        assertThat((List<String>) ((Map<String, Object>) ((List<?>) config.get("employees")).get(0)).get("skills"))
                .containsExactly(skillKey);
        assertThat((List<Map<String, Object>>) config.get("skill_requirements"))
                .containsExactly(Map.of("shift", OPEN, "skill", skillKey, "count", 1));
        assertThat(response.getShortages()).singleElement().satisfies(s -> {
            assertThat(s.skillName()).isEqualTo("일본어");
            assertThat(s.shiftName()).isEqualTo("오픈");
            assertThat(s.date()).isEqualTo(WEEK_START);
        });
    }

    @Test
    void 휴무_신청을_엔진에_넘기고_후보안마다_휴무일_배정을_표시한다() {
        dayOffRequestRepository.save(new DayOffRequest(employee, WEEK_START, DayOffType.MUST, null));
        dayOffRequestRepository.save(new DayOffRequest(employee, WEEK_START.plusDays(SAT), DayOffType.PREFER, null));
        // 다음 주 신청은 이번 주 config에 들어가면 안 된다
        dayOffRequestRepository.save(new DayOffRequest(employee, WEEK_START.plusWeeks(1), DayOffType.MUST, null));

        // 엔진이 월요일(필수 휴무)에 배정한 결과를 돌려준 경우
        when(pythonEngineRunner.run(any())).thenReturn(Map.of(
                "status", "OPTIMAL", "cost", 0, "solve_ms", 1, "shortage_report", List.of(),
                "schedule", List.of(assigned(employee, MON, OPEN))));

        ScheduleProposalResponse response = automationService.generateProposal(store.getId(), WEEK_START, "owner");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pythonEngineRunner).run(captor.capture());
        Map<String, Object> employeeConfig = (Map<String, Object>) ((List<?>) captor.getValue().get("employees")).get(0);
        assertThat((List<Map<String, Object>>) employeeConfig.get("off_requests")).containsExactlyInAnyOrder(
                Map.of("day", MON, "type", "must"),
                Map.of("day", SAT, "type", "prefer"));
        assertThat(response.getCandidates().get(0).dayOffViolations()).singleElement().satisfies(v -> {
            assertThat(v.date()).isEqualTo(WEEK_START);
            assertThat(v.type()).isEqualTo("MUST");
        });
    }

    private Map<String, Object> assigned(Employee e, int... dayShiftPairs) {
        List<Map<String, Integer>> slots = new java.util.ArrayList<>();
        for (int i = 0; i < dayShiftPairs.length; i += 2) {
            slots.add(Map.of("day", dayShiftPairs[i], "shift", dayShiftPairs[i + 1]));
        }
        return Map.of("employee_id", "emp_" + e.getId(), "assignments", slots);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateAndCaptureConfig() {
        automationService.generateProposal(store.getId(), WEEK_START, "owner");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pythonEngineRunner).run(captor.capture());
        return captor.getValue();
    }

    private Map<String, Integer> slot(int day, int shift) {
        return Map.of("day", day, "shift", shift);
    }

    private void shift(String name, LocalTime start, LocalTime end, DayOfWeek dayOfWeek) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(start);
        shift.setEndTime(end);
        shift.setRequiredStaff(1);
        shift.setDayOfWeek(dayOfWeek);
        shift.setStore(store);
        shiftRepository.save(shift);
    }
}
