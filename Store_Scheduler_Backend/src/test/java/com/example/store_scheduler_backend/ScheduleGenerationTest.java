package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.DayOffRequest;
import com.example.store_scheduler_backend.domain.DayOffType;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmploymentType;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.ShiftSkillRequirement;
import com.example.store_scheduler_backend.domain.Skill;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.DayOffRequestRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.ShiftSkillRequirementRepository;
import com.example.store_scheduler_backend.repository.SkillRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 CP-SAT 엔진을 실행해 근무표 생성 → 저장까지 검증한다.
 * python3 + ortools가 없는 환경에서는 건너뛴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScheduleGenerationTest {

    private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 2); // 월요일

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired SkillRepository skillRepository;
    @Autowired DayOffRequestRepository dayOffRequestRepository;
    @Autowired ShiftSkillRequirementRepository requirementRepository;

    private Store store;
    private Employee weekdayStaff;
    private Employee midweekStaff;
    private Employee pendingStaff;

    @BeforeAll
    static void requireEngine() {
        assumeTrue(commandSucceeds("python3", "-c", "import ortools"), "python3/ortools가 없어 건너뜀");
    }

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(new User("owner", "pw", Role.MANAGER));
        store = storeRepository.save(new Store("테스트매장", null, owner, "GENCODE1"));

        weekdayStaff = approvedEmployee("월화수", 10030);
        midweekStaff = approvedEmployee("수목", 11000);
        pendingStaff = employeeRepository.save(new Employee("대기", null, store,
                userRepository.save(new User("pending", "pw", Role.EMPLOYEE))));

        available(weekdayStaff, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        available(midweekStaff, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
        available(pendingStaff, DayOfWeek.values());

        shift("오픈", LocalTime.of(9, 0), LocalTime.of(13, 0));
        shift("마감", LocalTime.of(22, 0), LocalTime.of(2, 0));
    }

    @Test
    void 후보안을_생성만_하면_근무표는_바뀌지_않는다() throws Exception {
        automate(WEEK_START)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.proposalStatus").value("PENDING"))
                .andExpect(jsonPath("$.candidates").value(not(empty())))
                .andExpect(jsonPath("$.candidates[0].assignments").value(not(empty())))
                // 금~일은 가용 직원이 없으므로 최소 인원 미달이 보고되어야 한다
                .andExpect(jsonPath("$.shortages").value(not(empty())));

        assertThat(scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId())).isEmpty();
    }

    @Test
    void 확정하면_근무표를_저장하고_가용_요일과_승인_직원만_배정한다() throws Exception {
        generateAndConfirm(WEEK_START)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedIndex").value(0));

        List<Schedule> saved = scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId());
        assertThat(saved).isNotEmpty();
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.getWorkDate()).isBetween(WEEK_START, WEEK_START.plusDays(6));
            assertThat(s.getEmployee().getId()).isNotEqualTo(pendingStaff.getId());
        });
        assertThat(daysOf(saved, weekdayStaff)).isSubsetOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        assertThat(daysOf(saved, midweekStaff)).isSubsetOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
        // 근무 시간은 Shift 설정을 그대로 따른다 (자정 넘김 포함)
        assertThat(saved).allSatisfy(s -> assertThat(s.getStartTime()).isIn(LocalTime.of(9, 0), LocalTime.of(22, 0)));
    }

    @Test
    void 같은_주를_다시_생성하면_기존_근무표를_교체하고_다른_주는_건드리지_않는다() throws Exception {
        Schedule nextWeek = new Schedule();
        nextWeek.setStore(store);
        nextWeek.setEmployee(weekdayStaff);
        nextWeek.setWorkDate(WEEK_START.plusWeeks(1));
        nextWeek.setStartTime(LocalTime.of(9, 0));
        nextWeek.setEndTime(LocalTime.of(13, 0));
        scheduleRepository.save(nextWeek);

        generateAndConfirm(WEEK_START).andExpect(status().isOk());
        int firstCount = countInWeek();
        generateAndConfirm(WEEK_START).andExpect(status().isOk());

        assertThat(countInWeek()).isEqualTo(firstCount);
        assertThat(scheduleRepository.findById(nextWeek.getId())).isPresent();
    }

    @Test
    void 가용_시간_밖의_교대에는_배정하지_않는다() throws Exception {
        // 금~일에 근무 가능한 사람은 이 직원뿐이고, 가용 시간은 저녁부터.
        // 엔진은 인건비가 싼 오픈을 선호하므로, 가용 시간이 반영되지 않으면 오픈에 배정된다.
        Employee eveningStaff = approvedEmployee("저녁형", 10030);
        for (DayOfWeek day : new DayOfWeek[]{DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY}) {
            availabilityRepository.save(new Availability(eveningStaff, day, LocalTime.of(18, 0), LocalTime.of(23, 59)));
        }

        generateAndConfirm(WEEK_START).andExpect(status().isOk());

        List<Schedule> evening = scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId()).stream()
                .filter(s -> s.getEmployee().getId().equals(eveningStaff.getId()))
                .toList();
        assertThat(evening).isNotEmpty();
        assertThat(evening).allSatisfy(s -> assertThat(s.getStartTime()).isEqualTo(LocalTime.of(22, 0)));
    }

    @Test
    void 필요_스킬이_있는_교대에는_스킬_보유자를_배정하고_못_채운_날은_보고한다() throws Exception {
        // 수·목에 근무 가능한 "수목"만 일본어 가능. 오픈에 일본어 가능자 1명 필요
        Skill japanese = skillRepository.save(new Skill("일본어", store));
        midweekStaff.addSkill(japanese);
        Shift open = shiftRepository.findByStoreId(store.getId()).stream()
                .filter(s -> s.getName().equals("오픈")).findFirst().orElseThrow();
        requirementRepository.save(new ShiftSkillRequirement(open, japanese, 1));

        String body = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        confirm(objectMapper.readTree(body).get("proposalId").asLong(), 0).andExpect(status().isOk());

        List<Schedule> saved = scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId());
        for (LocalDate day : List.of(WEEK_START.plusDays(2), WEEK_START.plusDays(3))) {
            assertThat(saved).filteredOn(s -> s.getWorkDate().equals(day) && s.getStartTime().equals(LocalTime.of(9, 0)))
                    .extracting(s -> s.getEmployee().getId())
                    .contains(midweekStaff.getId());
        }
        // 월·화 오픈은 일본어 가능자가 없어 역량 부족으로 보고된다
        var skillShortageDates = new java.util.HashSet<String>();
        objectMapper.readTree(body).get("shortages").forEach(s -> {
            if (!s.get("skillName").isNull() && s.get("skillName").asText().equals("일본어")) {
                skillShortageDates.add(s.get("date").asText());
            }
        });
        assertThat(skillShortageDates).contains(WEEK_START.toString(), WEEK_START.plusDays(1).toString());
    }

    @Test
    void 대신할_사람이_있으면_필수_휴무를_신청한_날에는_배정하지_않는다() throws Exception {
        // 화요일 오픈·마감을 맡을 수 있는 직원 2명을 추가 → 월화수 직원의 화요일 필수 휴무를 지킬 수 있다
        for (String name : List.of("화요일1", "화요일2")) {
            available(approvedEmployee(name, 10030), DayOfWeek.TUESDAY);
        }
        dayOffRequestRepository.save(new DayOffRequest(weekdayStaff, WEEK_START.plusDays(1), DayOffType.MUST, "병원"));

        String body = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("candidates").get(0).get("dayOffViolations").size()).isZero();
        confirm(objectMapper.readTree(body).get("proposalId").asLong(), 0).andExpect(status().isOk());

        assertThat(daysOf(scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId()), weekdayStaff))
                .isNotEmpty()
                .doesNotContain(DayOfWeek.TUESDAY);
    }

    @Test
    void 대신할_사람이_없어_필수_휴무일에_배정되면_후보안에_위반으로_표시한다() throws Exception {
        // 엔진 우선순위: 최소 인원(5천만) > 필수 휴무(1천만). 화요일 가능자가 이 직원뿐이면 배정되고 위반으로 보고된다
        dayOffRequestRepository.save(new DayOffRequest(weekdayStaff, WEEK_START.plusDays(1), DayOffType.MUST, "병원"));

        String body = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        var violations = objectMapper.readTree(body).get("candidates").get(0).get("dayOffViolations");
        assertThat(violations.size()).isEqualTo(1);
        assertThat(violations.get(0).get("employeeName").asText()).isEqualTo("월화수");
        assertThat(violations.get(0).get("date").asText()).isEqualTo(WEEK_START.plusDays(1).toString());
        assertThat(violations.get(0).get("type").asText()).isEqualTo("MUST");
        assertThat(reasonsOf(body, weekdayStaff, WEEK_START.plusDays(1), LocalTime.of(9, 0)))
                .contains("필수 휴무 신청일이지만 배정됨");
    }

    @Test
    void 근무마다_배정_사유를_붙이고_다시_생성하면_기존_근무_유지를_표시한다() throws Exception {
        Skill japanese = skillRepository.save(new Skill("일본어", store));
        midweekStaff.addSkill(japanese);
        Shift open = shiftRepository.findByStoreId(store.getId()).stream()
                .filter(s -> s.getName().equals("오픈")).findFirst().orElseThrow();
        requirementRepository.save(new ShiftSkillRequirement(open, japanese, 1));

        String first = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        objectMapper.readTree(first).get("candidates").get(0).get("assignments").forEach(a -> {
            List<String> reasons = new java.util.ArrayList<>();
            a.get("reasons").forEach(r -> reasons.add(r.asText()));
            assertThat(reasons).contains("가용 시간 00:00~23:59").anyMatch(r -> r.startsWith("이번 주 "));
            assertThat(reasons).doesNotContain("기존 근무표 유지");
        });
        assertThat(reasonsOf(first, midweekStaff, WEEK_START.plusDays(3), LocalTime.of(9, 0)))
                .contains("필요 스킬 보유: 일본어");

        confirm(objectMapper.readTree(first).get("proposalId").asLong(), 0).andExpect(status().isOk());
        String second = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(reasonsOf(second, midweekStaff, WEEK_START.plusDays(3), LocalTime.of(9, 0)))
                .startsWith("기존 근무표 유지", "필요 스킬 보유: 일본어");
    }

    @Test
    void 필수_휴무_보장을_켜면_대신할_사람이_없어도_배정하지_않고_인원_부족으로_보고한다() throws Exception {
        dayOffRequestRepository.save(new DayOffRequest(weekdayStaff, WEEK_START.plusDays(1), DayOffType.MUST, "병원"));
        mockMvc.perform(patch("/api/stores/{storeId}/settings", store.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER"))
                        .contentType("application/json").content("{\"guaranteeMustDayOff\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guaranteeMustDayOff").value(true));

        String body = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        var result = objectMapper.readTree(body);
        assertThat(result.get("candidates").get(0).get("dayOffViolations").size()).isZero();
        var tuesdayShortages = new java.util.ArrayList<String>();
        result.get("shortages").forEach(s -> {
            if (s.get("date").asText().equals(WEEK_START.plusDays(1).toString())) {
                tuesdayShortages.add(s.get("shiftName").asText());
            }
        });
        assertThat(tuesdayShortages).contains("오픈", "마감");
    }

    @Test
    void 알바와_정규직이_같은_날_필수_휴무면_정규직을_배정하고_예비_대타와_알림을_준다() throws Exception {
        // 화요일에만 있는 오픈 1명. 화요일 가능자는 둘뿐이고 둘 다 필수 휴무 → 최소 인원 때문에 한 명은 배정된다
        store = storeRepository.save(new Store("휴무충돌매장", null, store.getOwner(), "GENCODE3"));
        Employee partTimer = approvedEmployee("알바", 10030);
        User fullUser = userRepository.save(new User("user-정규", "pw", Role.EMPLOYEE));
        Employee fullTimer = new Employee("정규", null, store, fullUser);
        fullTimer.approve(10030, EmploymentType.FULL_TIME);
        employeeRepository.save(fullTimer);
        available(partTimer, DayOfWeek.TUESDAY);
        available(fullTimer, DayOfWeek.TUESDAY);
        Shift open = new Shift();
        open.setName("오픈");
        open.setStartTime(LocalTime.of(9, 0));
        open.setEndTime(LocalTime.of(13, 0));
        open.setRequiredStaff(1);
        open.setDayOfWeek(DayOfWeek.TUESDAY);
        open.setStore(store);
        shiftRepository.save(open);
        LocalDate tuesday = WEEK_START.plusDays(1);
        dayOffRequestRepository.save(new DayOffRequest(partTimer, tuesday, DayOffType.MUST, "시험"));
        dayOffRequestRepository.save(new DayOffRequest(fullTimer, tuesday, DayOffType.MUST, "개인 사정"));

        String body = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode result = objectMapper.readTree(body);
        JsonNode violation = result.get("candidates").get(0).get("dayOffViolations").get(0);
        assertThat(violation.get("employeeName").asText()).isEqualTo("정규");
        assertThat(violation.get("shiftName").asText()).isEqualTo("오픈");
        JsonNode backup = violation.get("backups").get(0);
        assertThat(backup.get("name").asText()).isEqualTo("알바");
        assertThat(backup.get("eligible").asBoolean()).isFalse();
        assertThat(backup.get("reasons").get(0).asText()).isEqualTo("필수 휴무 신청일");
        JsonNode notice = result.get("notice");
        assertThat(notice.get("mustDayOffViolationCount").asInt()).isEqualTo(1);
        assertThat(notice.get("summary").asText()).contains("필수 휴무 위반 1건");
    }

    @Test
    void 다른_매장_관리자는_매장_설정을_바꿀_수_없다() throws Exception {
        userRepository.save(new User("otherOwner", "pw", Role.MANAGER));
        mockMvc.perform(patch("/api/stores/{storeId}/settings", store.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.createToken("otherOwner", "MANAGER"))
                        .contentType("application/json").content("{\"guaranteeMustDayOff\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 공정_우선은_근무일을_고르게_나누고_인건비_우선은_더_저렴하다() throws Exception {
        // 매일 오픈 1명, 시급만 다른 두 직원. 연속 5일 금지라 한 명이 최대 6일까지 가능
        store = storeRepository.save(new Store("시급차이매장", null, store.getOwner(), "GENCODE2"));
        Employee cheap = approvedEmployee("저시급", 10030);
        Employee expensive = approvedEmployee("고시급", 15000);
        available(cheap, DayOfWeek.values());
        available(expensive, DayOfWeek.values());
        shift("오픈", LocalTime.of(9, 0), LocalTime.of(13, 0));

        String body = automate(WEEK_START).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        var candidates = objectMapper.readTree(body).get("candidates");
        var fairness = candidateLabeled(candidates, "공정 우선");
        var cost = candidateLabeled(candidates, "인건비 우선");
        var fairDays = new java.util.ArrayList<Integer>();
        fairness.get("employees").forEach(e -> fairDays.add(e.get("workDays").asInt()));
        assertThat(fairDays).containsExactlyInAnyOrder(4, 3);
        assertThat(cost.get("estimatedCost").asLong()).isLessThan(fairness.get("estimatedCost").asLong());
    }

    @Test
    void 시작일이_월요일이_아니면_거절한다() throws Exception {
        automate(WEEK_START.plusDays(1)).andExpect(status().isBadRequest());
    }

    @Test
    void 이미_확정한_후보안은_다시_확정할_수_없다() throws Exception {
        long proposalId = createProposal(WEEK_START);
        confirm(proposalId, 0).andExpect(status().isOk());
        confirm(proposalId, 0).andExpect(status().isBadRequest());
    }

    @Test
    void 존재하지_않는_후보안_번호는_거절한다() throws Exception {
        long proposalId = createProposal(WEEK_START);
        confirm(proposalId, 99).andExpect(status().isBadRequest());
        assertThat(scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId())).isEmpty();
    }

    @Test
    void 다른_매장_관리자는_후보안을_조회하거나_확정할_수_없다() throws Exception {
        long proposalId = createProposal(WEEK_START);
        userRepository.save(new User("otherOwner", "pw", Role.MANAGER));
        String otherToken = "Bearer " + jwtTokenProvider.createToken("otherOwner", "MANAGER");

        mockMvc.perform(get("/api/stores/{storeId}/schedules/proposals/{id}", store.getId(), proposalId)
                        .header("Authorization", otherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/stores/{storeId}/schedules/proposals/{id}/confirm", store.getId(), proposalId)
                        .header("Authorization", otherToken)
                        .contentType("application/json").content("{\"candidateIndex\":0}"))
                .andExpect(status().isForbidden());
    }

    private long createProposal(LocalDate weekStart) throws Exception {
        String body = automate(weekStart).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("proposalId").asLong();
    }

    private ResultActions confirm(long proposalId, int candidateIndex) throws Exception {
        return mockMvc.perform(post("/api/stores/{storeId}/schedules/proposals/{id}/confirm", store.getId(), proposalId)
                .header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER"))
                .contentType("application/json")
                .content("{\"candidateIndex\":" + candidateIndex + "}"));
    }

    private ResultActions generateAndConfirm(LocalDate weekStart) throws Exception {
        return confirm(createProposal(weekStart), 0);
    }

    private ResultActions automate(LocalDate weekStart) throws Exception {
        return mockMvc.perform(post("/api/stores/{storeId}/schedules/automate", store.getId())
                .param("weekStart", weekStart.toString())
                .header("Authorization", "Bearer " + jwtTokenProvider.createToken("owner", "MANAGER")));
    }

    private int countInWeek() {
        return (int) scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(store.getId()).stream()
                .filter(s -> !s.getWorkDate().isAfter(WEEK_START.plusDays(6)))
                .count();
    }

    private JsonNode candidateLabeled(JsonNode candidates, String label) {
        for (JsonNode c : candidates) {
            if (List.of(c.get("label").asText().split(" · ")).contains(label)) {
                return c;
            }
        }
        throw new AssertionError("후보안 없음: " + label);
    }

    private List<String> reasonsOf(String body, Employee employee, LocalDate date, LocalTime start) throws Exception {
        for (var a : objectMapper.readTree(body).get("candidates").get(0).get("assignments")) {
            if (a.get("employeeId").asLong() == employee.getId() && a.get("workDate").asText().equals(date.toString())
                    && LocalTime.parse(a.get("startTime").asText()).equals(start)) {
                List<String> reasons = new java.util.ArrayList<>();
                a.get("reasons").forEach(r -> reasons.add(r.asText()));
                return reasons;
            }
        }
        throw new AssertionError("배정 없음: " + employee.getName() + " " + date + " " + start);
    }

    private Set<DayOfWeek> daysOf(List<Schedule> schedules, Employee employee) {
        return schedules.stream()
                .filter(s -> s.getEmployee().getId().equals(employee.getId()))
                .map(s -> s.getWorkDate().getDayOfWeek())
                .collect(java.util.stream.Collectors.toSet());
    }

    private Employee approvedEmployee(String name, int wage) {
        User user = userRepository.save(new User("user-" + name, "pw", Role.EMPLOYEE));
        Employee employee = new Employee(name, null, store, user);
        employee.approve(wage);
        return employeeRepository.save(employee);
    }

    private void available(Employee employee, DayOfWeek... days) {
        for (DayOfWeek day : days) {
            availabilityRepository.save(new Availability(employee, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
        }
    }

    private void shift(String name, LocalTime start, LocalTime end) {
        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(start);
        shift.setEndTime(end);
        shift.setRequiredStaff(1);
        shift.setStore(store);
        shiftRepository.save(shift);
    }

    private static boolean commandSucceeds(String... command) {
        try {
            return new ProcessBuilder(command).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
