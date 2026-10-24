package com.example.store_scheduler_backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔진에 넘기는 근무 시간대(Shift) 변환 규칙 검증
 */
class ScheduleAutomationServiceTest {

    @Test
    void 근무_시간은_자정을_넘겨도_양수로_계산한다() {
        assertThat(ScheduleAutomationService.shiftHours(LocalTime.of(9, 0), LocalTime.of(13, 0))).isEqualTo(4);
        assertThat(ScheduleAutomationService.shiftHours(LocalTime.of(22, 0), LocalTime.of(2, 0))).isEqualTo(4);
        assertThat(ScheduleAutomationService.shiftHours(LocalTime.of(18, 0), LocalTime.of(0, 0))).isEqualTo(6);
    }

    @Test
    void 삼십분_단위_근무는_반올림한다() {
        assertThat(ScheduleAutomationService.shiftHours(LocalTime.of(9, 0), LocalTime.of(13, 30))).isEqualTo(5);
        assertThat(ScheduleAutomationService.shiftHours(LocalTime.of(9, 0), LocalTime.of(13, 20))).isEqualTo(4);
    }

    @Test
    void 야간_시간대에_걸치면_야간_근무로_본다() {
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(22, 0), LocalTime.of(6, 0))).isTrue();
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(18, 0), LocalTime.of(23, 0))).isTrue();
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(5, 0), LocalTime.of(9, 0))).isTrue();
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(18, 0), LocalTime.of(0, 0))).isTrue();
    }

    @Test
    void 주간_근무는_야간으로_보지_않는다() {
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(9, 0), LocalTime.of(13, 0))).isFalse();
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(18, 0), LocalTime.of(22, 0))).isFalse();
        assertThat(ScheduleAutomationService.isNightShift(LocalTime.of(6, 0), LocalTime.of(10, 0))).isFalse();
    }

    @Test
    void 교대가_가용_시간_안에_완전히_들어가야_근무_가능하다() {
        LocalTime from = LocalTime.of(9, 0);
        LocalTime to = LocalTime.of(18, 0);
        assertThat(ScheduleAutomationService.fitsAvailability(LocalTime.of(9, 0), LocalTime.of(13, 0), from, to)).isTrue();
        assertThat(ScheduleAutomationService.fitsAvailability(LocalTime.of(14, 0), LocalTime.of(18, 0), from, to)).isTrue();
        assertThat(ScheduleAutomationService.fitsAvailability(LocalTime.of(8, 0), LocalTime.of(12, 0), from, to)).isFalse();
        assertThat(ScheduleAutomationService.fitsAvailability(LocalTime.of(15, 0), LocalTime.of(19, 0), from, to)).isFalse();
    }

    @Test
    void 자정을_넘기는_교대는_가용_시간이_그날_끝까지일_때만_가능하다() {
        LocalTime start = LocalTime.of(22, 0);
        LocalTime end = LocalTime.of(2, 0);
        assertThat(ScheduleAutomationService.fitsAvailability(start, end, LocalTime.of(18, 0), LocalTime.of(23, 59))).isTrue();
        assertThat(ScheduleAutomationService.fitsAvailability(start, end, LocalTime.of(18, 0), LocalTime.of(23, 0))).isFalse();
        assertThat(ScheduleAutomationService.fitsAvailability(start, end, LocalTime.of(23, 0), LocalTime.of(23, 59))).isFalse();
    }
}
