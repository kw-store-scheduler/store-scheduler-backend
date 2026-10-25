package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.DayOffFallback;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.FixedScheduleCategory;
import com.example.store_scheduler_backend.domain.ShiftAttitude;
import com.example.store_scheduler_backend.domain.UnavailablePeriod;
import com.example.store_scheduler_backend.domain.EmployeePermission;
import com.example.store_scheduler_backend.domain.EmployeeSkillLevel;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.SkillLevel;
import com.example.store_scheduler_backend.domain.SkillWindowRequirement;
import com.example.store_scheduler_backend.domain.FixedSchedule;
import com.example.store_scheduler_backend.domain.PreferenceLevel;
import com.example.store_scheduler_backend.domain.PreferencePriority;
import com.example.store_scheduler_backend.domain.PreferenceSubmission;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftDayStaffing;
import com.example.store_scheduler_backend.domain.ShiftPreference;
import com.example.store_scheduler_backend.domain.StaffingException;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.EmployeeSkillLevelRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.SkillWindowRequirementRepository;
import com.example.store_scheduler_backend.repository.FixedScheduleRepository;
import com.example.store_scheduler_backend.repository.PreferenceSubmissionRepository;
import com.example.store_scheduler_backend.repository.ShiftDayStaffingRepository;
import com.example.store_scheduler_backend.repository.ShiftPreferenceRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.StaffingExceptionRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UnavailablePeriodRepository;
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
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 희망 제출·요일별 인원·예외일·개인 규칙이 엔진 config에 정확히 들어가는지 검증한다. 엔진은 mock
 *
 * 교대 인덱스(시작 시간순): 0=오픈 09~13, 1=미들 12~17, 2=마감 17~22
 */
@SpringBootTest
@Transactional
class PreferenceEngineConfigTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 2); // 월요일
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);      // 11/1이 일요일
    private static final int OPEN = 0;
    private static final int MIDDLE = 1;
    private static final int CLOSE = 2;

    @MockitoBean PythonEngineRunner pythonEngineRunner;

    @Autowired ScheduleAutomationService automationService;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ShiftPreferenceRepository preferenceRepository;
    @Autowired FixedScheduleRepository fixedScheduleRepository;
    @Autowired PreferenceSubmissionRepository submissionRepository;
    @Autowired ShiftDayStaffingRepository dayStaffingRepository;
    @Autowired StaffingExceptionRepository exceptionRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;
    @Autowired SkillRepository skillRepository;
    @Autowired EmployeeSkillLevelRepository levelRepository;
    @Autowired SkillWindowRequirementRepository windowRepository;
    @Autowired UnavailablePeriodRepository unavailablePeriodRepository;

    private Store store;
    private Employee employee;
    private Shift open;
    private Shift middle;
    private Shift close;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("희망매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "PREFCD01"));
        employee = new Employee("유나", null, store, userRepository.save(new User("yuna", "pw", Role.EMPLOYEE)));
        employee.approve(10030);
        employeeRepository.save(employee);
        for (DayOfWeek day : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(employee, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
        }
        close = shift("마감", 17, 22);
        open = shift("오픈", 9, 13);
        middle = shift("미들", 12, 17);

        when(pythonEngineRunner.run(any())).thenReturn(Map.of(
                "status", "OPTIMAL", "cost", 0, "solve_ms", 1, "shortage_report", List.of(), "schedule", List.of()));
    }

    @Test
    void 희망_칸을_낸_직원은_가용_시간_대신_제출한_칸으로_넘기고_고정_일정은_다시_막는다() {
        preferenceRepository.save(new ShiftPreference(employee, WEEK_START, open, PreferenceLevel.PREFERRED));
        preferenceRepository.save(new ShiftPreference(employee, WEEK_START, close, PreferenceLevel.AVAILABLE));
        preferenceRepository.save(new ShiftPreference(employee, WEEK_START.plusDays(2), middle, PreferenceLevel.AVAILABLE));
        // 제출 뒤에 생긴 수요일 12~13시 수업
        fixedScheduleRepository.save(new FixedSchedule(employee, DayOfWeek.WEDNESDAY, LocalTime.of(12, 0), LocalTime.of(13, 0), "수업"));

        Map<String, Object> employeeConfig = employeeConfig(weekly());

        assertThat((List<Integer>) employeeConfig.get("available_days")).containsExactly(0, 2);
        assertThat((List<Map<String, Integer>>) employeeConfig.get("unavailable_slots")).containsExactlyInAnyOrder(
                slot(0, MIDDLE), slot(2, OPEN), slot(2, CLOSE),
                slot(2, MIDDLE)); // 수업과 겹침
        assertThat((List<Map<String, Integer>>) employeeConfig.get("preferred_slots")).containsExactly(slot(0, OPEN));
        assertThat(employeeConfig).doesNotContainKey("preferred_shifts");
    }

    @Test
    void 요일별_인원과_예외일을_날짜별_인원으로_넘긴다() {
        dayStaffingRepository.save(new ShiftDayStaffing(open, DayOfWeek.SATURDAY, 2, 3));
        exceptionRepository.save(new StaffingException(store, WEEK_START.plusDays(2), null, 0, 0, "휴점"));
        exceptionRepository.save(new StaffingException(store, WEEK_START.plusDays(3), close, 1, 2, "단체 예약"));

        Map<String, Object> config = weekly();

        List<List<Integer>> min = (List<List<Integer>>) config.get("min_staff_by_day");
        List<List<Integer>> target = (List<List<Integer>>) config.get("target_staff_by_day");
        assertThat(min.get(5).get(OPEN)).isEqualTo(2);
        assertThat(target.get(5).get(OPEN)).isEqualTo(3);
        assertThat(target.get(2)).containsExactly(0, 0, 0);
        assertThat(target.get(3)).containsExactly(1, 1, 2);
        assertThat(target.get(0)).containsExactly(1, 1, 1);
        assertThat(config.get("max_staff_by_day")).isEqualTo(target);
    }

    @Test
    void 월간_생성은_개인_규칙과_우선순위_배율을_넘기고_주간_생성은_넘기지_않는다() {
        PreferenceSubmission submission = new PreferenceSubmission(employee, NOVEMBER.atDay(1));
        submission.update(2, 60, 80, 3, true, List.of(PreferencePriority.SHIFT, PreferencePriority.RHYTHM));
        submissionRepository.save(submission);

        Map<String, Object> config = monthly();
        Map<String, Object> employeeConfig = employeeConfig(config);

        assertThat(config.get("num_days")).isEqualTo(30);
        assertThat((List<?>) config.get("weeks")).hasSize(6);
        assertThat((List<Integer>) config.get("weekend_days")).hasSize(9).contains(0, 6, 7);
        assertThat(config.get("time_limit")).isEqualTo(20);
        assertThat(config.get("open_shifts")).isEqualTo(List.of(OPEN));
        assertThat(config.get("close_shifts")).isEqualTo(List.of(CLOSE));
        assertThat(employeeConfig.get("max_weekend_shifts")).isEqualTo(2);
        assertThat(employeeConfig.get("min_total_hours")).isEqualTo(60);
        assertThat(employeeConfig.get("max_total_hours")).isEqualTo(80);
        assertThat(employeeConfig.get("max_consecutive_days")).isEqualTo(3);
        assertThat(employeeConfig.get("no_open_after_close")).isEqualTo(true);
        assertThat(employeeConfig.get("weight_scale")).isEqualTo(Map.of("PREF_W", 2.0, "RHYTHM_W", 1.6, "CONSEC_W", 1.6));
        // 제출했지만 칸을 하나도 고르지 않았으면 그달은 근무 불가
        assertThat((List<?>) employeeConfig.get("available_days")).isEmpty();

        Map<String, Object> weeklyEmployee = employeeConfig(weekly());
        assertThat(weeklyEmployee).doesNotContainKeys("max_weekend_shifts", "weight_scale");
    }

    @Test
    void 오후만_쉬기_대안은_오후_교대에_휴무_요청을_한_번_더_건다() {
        dayOffRequestRepository.save(new DayOffRequest(employee, WEEK_START, DayOffType.PREFER, "시험",
                List.of(DayOffFallback.AFTERNOON_OFF, DayOffFallback.SWAP_DAY)));

        assertThat((List<Map<String, Object>>) employeeConfig(weekly()).get("off_requests")).containsExactlyInAnyOrder(
                Map.of("day", 0, "type", "prefer"),
                Map.of("day", 0, "type", "prefer", "shifts", List.of(MIDDLE, CLOSE)));
    }

    @Test
    void 쪼개기_근무를_켜면_하루_2교대와_겹치는_교대_쌍을_넘긴다() {
        assertThat(weekly()).doesNotContainKey("max_shifts_per_day");

        store.updateSettings(null, null, true, null);
        Map<String, Object> config = weekly();

        assertThat(config.get("max_shifts_per_day")).isEqualTo(2);
        assertThat(config.get("conflicting_shifts")).isEqualTo(List.of(List.of(OPEN, MIDDLE)));
    }

    @Test
    void 확정_등급은_등급별_키로_맡길_수_있는_일과_수습은_엔진_입력으로_넘긴다() {
        Skill service = skillRepository.save(new Skill("응대", store));
        employee.addSkill(service);
        EmployeeSkillLevel level = new EmployeeSkillLevel(employee, service);
        level.confirm(SkillLevel.MID);
        levelRepository.save(level);
        employee.updatePermissions(List.of(EmployeePermission.STORE_KEY));
        employee.updateProbation(WEEK_START.plusDays(1));

        Map<String, Object> employeeConfig = employeeConfig(weekly());

        String key = "skill_" + service.getId();
        assertThat((List<String>) employeeConfig.get("skills"))
                .containsExactlyInAnyOrder(key, key + "@LOW", key + "@MID", "perm_STORE_KEY");
        assertThat(employeeConfig.get("mentor_required_days")).isEqualTo(List.of(0, 1));
    }

    @Test
    void 시간대_역량은_겹치는_교대_전부와_해당_요일로_넘기고_우선은_낮은_패널티다() {
        Skill service = skillRepository.save(new Skill("응대", store));
        windowRepository.save(new SkillWindowRequirement(store, "피크", LocalTime.of(12, 0), LocalTime.of(14, 0), service,
                SkillLevel.HIGH, null, 1, true, List.of()));
        windowRepository.save(new SkillWindowRequirement(store, null, LocalTime.of(10, 0), LocalTime.of(12, 0), null,
                null, EmployeePermission.ORDERING, 1, false, List.of(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)));

        List<Map<String, Object>> requirements = (List<Map<String, Object>>) weekly().get("skill_requirements");

        assertThat(requirements).hasSize(2);
        // 시작 시각 순: 10-12 발주 → 12-14 피크
        Map<String, Object> peak = requirements.get(1);
        assertThat(peak.get("skill")).isEqualTo("skill_" + service.getId() + "@HIGH");
        assertThat(peak.get("shifts")).isEqualTo(List.of(OPEN, MIDDLE));
        assertThat((List<?>) peak.get("days")).hasSize(7);
        assertThat(peak).doesNotContainKey("weight");
        Map<String, Object> ordering = requirements.get(0);
        assertThat(ordering.get("skill")).isEqualTo("perm_ORDERING");
        assertThat(ordering.get("shifts")).isEqualTo(List.of(OPEN));
        assertThat(ordering.get("days")).isEqualTo(List.of(1, 4));
        assertThat(ordering.get("weight")).isEqualTo(300_000);
    }

    @Test
    void 정규직은_교대_선호와_휴무_붙이기를_넘기고_가용_시간이_없어도_매일_가능하다() {
        Employee jisu = new Employee("지수", null, store, userRepository.save(new User("jisu", "pw", Role.EMPLOYEE)));
        jisu.fillJoinProfile(null, null, false, EmploymentType.FULL_TIME);
        jisu.approve(12_000);
        employeeRepository.save(jisu);
        PreferenceSubmission submission = new PreferenceSubmission(jisu, NOVEMBER.atDay(1));
        submission.update(null, null, null, null, true, List.of());
        submission.updateFullTimeOptions(Map.of(open.getId(), ShiftAttitude.PREFER, close.getId(), ShiftAttitude.AVOID), true);
        submissionRepository.save(submission);

        Map<String, Object> config = employeeConfigOf(weekly(), jisu);

        assertThat((List<Integer>) config.get("available_days")).hasSize(7);
        assertThat(config.get("preferred_shifts")).isEqualTo(List.of(OPEN));
        assertThat(config.get("avoid_shifts")).isEqualTo(List.of(CLOSE));
        assertThat(config.get("rest_days_together")).isEqualTo(true);
        assertThat(config).doesNotContainKey("preferred_slots");
    }

    @Test
    void 월간_근무량은_계약_범위가_기본이고_승인_없이_계약_상한을_넘지_않는다() {
        employee.updateContract(15, 20);
        PreferenceSubmission submission = new PreferenceSubmission(employee, NOVEMBER.atDay(1));
        submission.update(null, null, 100, null, false, List.of());
        submission.decideContractExceed(false);
        submissionRepository.save(submission);

        Map<String, Object> config = employeeConfig(monthly());
        assertThat(config.get("min_total_hours")).isEqualTo(64);
        assertThat(config.get("max_total_hours")).isEqualTo(86);

        submission.decideContractExceed(true);
        assertThat(employeeConfig(monthly()).get("max_total_hours")).isEqualTo(100);
    }

    @Test
    void 다른_직장_시간은_주_최대_시간에서_빼고_이번_달_예외_기간은_그날_전부_막는다() {
        fixedScheduleRepository.save(new FixedSchedule(employee, DayOfWeek.FRIDAY, LocalTime.of(18, 0), LocalTime.of(22, 0), "편의점",
                FixedScheduleCategory.OTHER_JOB, null, null));
        unavailablePeriodRepository.save(new UnavailablePeriod(employee, WEEK_START.plusDays(1), WEEK_START.plusDays(1), false, "시험"));

        Map<String, Object> config = employeeConfig(weekly());

        assertThat(config.get("max_weekly_hours")).isEqualTo(48);
        assertThat((List<Map<String, Integer>>) config.get("unavailable_slots"))
                .contains(slot(1, OPEN), slot(1, MIDDLE), slot(1, CLOSE), slot(4, CLOSE));
    }

    @Test
    void 연차를_대안으로_둔_휴무는_정규직_필수_휴무만큼_반차는_오후_교대만_지킨다() {
        dayOffRequestRepository.save(new DayOffRequest(employee, WEEK_START, DayOffType.PREFER, null, List.of(DayOffFallback.ANNUAL_LEAVE)));
        dayOffRequestRepository.save(new DayOffRequest(employee, WEEK_START.plusDays(1), DayOffType.PREFER, null,
                List.of(DayOffFallback.HALF_DAY)));

        assertThat((List<Map<String, Object>>) employeeConfig(weekly()).get("off_requests")).containsExactlyInAnyOrder(
                Map.of("day", 0, "type", "prefer", "weight", 9_000_000),
                Map.of("day", 1, "type", "prefer"),
                Map.of("day", 1, "type", "must", "shifts", List.of(MIDDLE, CLOSE), "weight", 9_000_000));
    }

    private Map<String, Object> employeeConfigOf(Map<String, Object> config, Employee target) {
        for (Object e : (List<?>) config.get("employees")) {
            Map<String, Object> map = (Map<String, Object>) e;
            if (map.get("id").equals("emp_" + target.getId())) {
                return map;
            }
        }
        throw new AssertionError("직원 없음: " + target.getId());
    }

    private Map<String, Object> weekly() {
        automationService.generateProposal(store.getId(), WEEK_START, "owner");
        return capture();
    }

    private Map<String, Object> monthly() {
        automationService.generateMonthlyProposal(store.getId(), NOVEMBER, "owner");
        return capture();
    }

    private Map<String, Object> capture() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(pythonEngineRunner, times(3)).run(captor.capture());
        clearInvocations(pythonEngineRunner);
        return captor.getAllValues().get(0);
    }

    private Map<String, Object> employeeConfig(Map<String, Object> config) {
        return (Map<String, Object>) ((List<?>) config.get("employees")).get(0);
    }

    private Map<String, Integer> slot(int day, int shift) {
        return Map.of("day", day, "shift", shift);
    }

    private Shift shift(String name, int startHour, int endHour) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(LocalTime.of(startHour, 0));
        shift.setEndTime(LocalTime.of(endHour, 0));
        shift.setRequiredStaff(1);
        shift.setStore(store);
        return shiftRepository.save(shift);
    }
}
