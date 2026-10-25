package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.OfferStatus;
import com.example.store_scheduler_backend.domain.Role;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.domain.SwapStatus;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.SwapOfferRepository;
import com.example.store_scheduler_backend.repository.SwapRequestRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import com.example.store_scheduler_backend.security.JwtTokenProvider;
import com.example.store_scheduler_backend.service.SwapRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결원 신고 → 동료 여러 명에게 대타 요청 → 먼저 수락한 1명 → 사장님 승인, 30분 만료, 일부 결원, 약속 점수
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SwapOfferFlowTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SwapRequestRepository swapRequestRepository;
    @Autowired SwapOfferRepository offerRepository;
    @Autowired SwapRequestService swapRequestService;

    private Store store;
    private Employee requester;
    private Employee jisu;
    private Employee hajun;
    private Schedule shift;

    @BeforeEach
    void setUp() {
        store = storeRepository.save(new Store("대타매장", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "SWAPCD01"));
        requester = employee("minho", "민호");
        jisu = employee("jisu", "지수");
        hajun = employee("hajun", "하준");
        shift = schedule(requester, TOMORROW, 9, 14);
    }

    @Test
    void 동료에게_보낸_요청은_먼저_수락한_한_명이_사장님_승인을_거쳐_확정된다() throws Exception {
        long requestId = report("{\"reasonCategory\":\"PERSONAL\",\"reason\":\"집안 일\"}");

        JsonNode candidates = body(as("minho", get("/api/stores/{s}/swap-requests/{r}/candidates", store.getId(), requestId)));
        assertThat(candidates.get(0).get("reasons").toString()).doesNotContain("시급");
        as("hajun", get("/api/stores/{s}/swap-requests/{r}/candidates", store.getId(), requestId))
                .andExpect(status().isForbidden());

        as("minho", post("/api/stores/{s}/swap-requests/{r}/offers", store.getId(), requestId)
                .contentType("application/json")
                .content("{\"employeeIds\":[" + jisu.getId() + "," + hajun.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFERED"));

        JsonNode offer = body(as("jisu", get("/api/stores/{s}/swap-offers/my", store.getId()))).get(0);
        assertThat(offer.get("expectedPay").asLong()).isEqualTo(5 * 10_000);
        assertThat(offer.get("weekDaysAfter").asInt()).isEqualTo(offer.get("weekDaysBefore").asInt() + 1);
        assertThat(offer.get("sentCount").asInt()).isEqualTo(2);
        assertThat(offer.get("needsOwnerApproval").asBoolean()).isTrue();
        assertThat(offer.get("reasons").toString()).contains("주 52시간");

        as("jisu", post("/api/stores/{s}/swap-offers/{o}/accept", store.getId(), offer.get("offerId").asLong()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        long hajunOffer = offerRepository.findBySwapRequestIdAndCandidateId(requestId, hajun.getId()).orElseThrow().getId();
        assertThat(offerRepository.findById(hajunOffer).orElseThrow().getStatus()).isEqualTo(OfferStatus.CLOSED);
        as("hajun", post("/api/stores/{s}/swap-offers/{o}/accept", store.getId(), hajunOffer))
                .andExpect(status().isBadRequest());

        JsonNode ownerView = body(as("owner", get("/api/stores/{s}/swap-requests/{r}/candidates", store.getId(), requestId)));
        for (JsonNode c : ownerView) {
            if (c.get("employeeId").asLong() == jisu.getId()) {
                assertThat(c.get("contactStatus").asText()).isEqualTo("PENDING_APPROVAL");
            }
        }

        as("owner", post("/api/stores/{s}/swap-requests/{r}/approve", store.getId(), requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(scheduleRepository.findById(shift.getId()).orElseThrow().getEmployee().getId()).isEqualTo(jisu.getId());
    }

    @Test
    void 사장님이_대타를_받아들이지_않으면_다시_찾는_상태로_돌아간다() throws Exception {
        long requestId = report("{}");
        sendOffer("minho", requestId, jisu);
        long offerId = offerRepository.findBySwapRequestIdAndCandidateId(requestId, jisu.getId()).orElseThrow().getId();
        as("jisu", post("/api/stores/{s}/swap-offers/{o}/accept", store.getId(), offerId)).andExpect(status().isOk());

        as("owner", post("/api/stores/{s}/swap-requests/{r}/reject-substitute", store.getId(), requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.substituteId").doesNotExist());
        assertThat(scheduleRepository.findById(shift.getId()).orElseThrow().getEmployee().getId()).isEqualTo(requester.getId());
    }

    @Test
    void 일부만_빠지면_직접_배정할_때_근무를_둘로_나눈다() throws Exception {
        as("minho", post("/api/stores/{s}/schedules/{id}/swap-requests", store.getId(), shift.getId())
                .contentType("application/json").content("{\"partialFrom\":\"15:00\"}"))
                .andExpect(status().isBadRequest());
        long requestId = report("{\"partialFrom\":\"11:00\",\"reasonCategory\":\"SICK\"}");

        as("owner", post("/api/stores/{s}/swap-requests/{r}/assign", store.getId(), requestId)
                .contentType("application/json").content("{\"employeeId\":" + hajun.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startTime").value("11:00:00"));

        assertThat(scheduleRepository.findById(shift.getId()).orElseThrow().getEndTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(scheduleRepository.findByEmployeeIdAndWorkDateBetweenOrderByWorkDateAsc(hajun.getId(), TOMORROW, TOMORROW))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.getStartTime()).isEqualTo(LocalTime.of(11, 0));
                    assertThat(s.getEndTime()).isEqualTo(LocalTime.of(14, 0));
                });
    }

    @Test
    void 삼십분_동안_아무도_수락하지_않으면_게시판으로_넘어간다() throws Exception {
        long requestId = report("{}");
        sendOffer("minho", requestId, jisu);

        assertThat(swapRequestService.expireStaleOffers(LocalDateTime.now().plusMinutes(10))).isZero();
        assertThat(swapRequestService.expireStaleOffers(LocalDateTime.now().plusMinutes(31))).isEqualTo(1);

        assertThat(swapRequestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(SwapStatus.REQUESTED);
        assertThat(offerRepository.findBySwapRequestIdAndCandidateId(requestId, jisu.getId()).orElseThrow().getStatus())
                .isEqualTo(OfferStatus.EXPIRED);
        assertThat(body(as("hajun", get("/api/stores/{s}/open-shifts", store.getId()))).get("items").size()).isEqualTo(1);
    }

    @Test
    void 개인_사정으로_빠지고_대타를_못_구하면_약속_점수가_깎이고_매달_절반씩_회복한다() throws Exception {
        long personal = report("{\"reasonCategory\":\"PERSONAL\"}");
        Schedule other = schedule(requester, TOMORROW.plusDays(1), 9, 14);
        as("minho", post("/api/stores/{s}/schedules/{id}/swap-requests", store.getId(), other.getId())
                .contentType("application/json").content("{\"reasonCategory\":\"SICK\"}"))
                .andExpect(status().isOk());

        assertThat(swapRequestService.settlePastRequests(TOMORROW.plusDays(3))).isEqualTo(2);

        assertThat(swapRequestRepository.findById(personal).orElseThrow().getStatus()).isEqualTo(SwapStatus.CANCELLED);
        assertThat(employeeRepository.findById(requester.getId()).orElseThrow().getPromiseScore()).isEqualTo(-1);
        requester.losePromisePoint();
        requester.losePromisePoint();
        swapRequestService.recoverPromiseScores();
        assertThat(requester.getPromiseScore()).isEqualTo(-1);
    }

    private void sendOffer(String username, long requestId, Employee candidate) throws Exception {
        as(username, post("/api/stores/{s}/swap-requests/{r}/offers", store.getId(), requestId)
                .contentType("application/json").content("{\"employeeIds\":[" + candidate.getId() + "]}"))
                .andExpect(status().isOk());
    }

    private long report(String json) throws Exception {
        return body(as("minho", post("/api/stores/{s}/schedules/{id}/swap-requests", store.getId(), shift.getId())
                .contentType("application/json").content(json))).get("id").asLong();
    }

    private Employee employee(String username, String name) {
        Employee employee = new Employee(name, null, store, userRepository.save(new User(username, "pw", Role.EMPLOYEE)));
        employee.approve(10_000);
        employeeRepository.save(employee);
        for (DayOfWeek day : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(employee, day, LocalTime.of(0, 0), LocalTime.of(23, 59)));
        }
        return employee;
    }

    private Schedule schedule(Employee employee, LocalDate date, int startHour, int endHour) {
        Schedule schedule = new Schedule();
        schedule.setEmployee(employee);
        schedule.setStore(store);
        schedule.setWorkDate(date);
        schedule.setStartTime(LocalTime.of(startHour, 0));
        schedule.setEndTime(LocalTime.of(endHour, 0));
        return scheduleRepository.save(schedule);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions as(String username, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", "Bearer " + jwtTokenProvider.createToken(username, "MANAGER")));
    }
}
