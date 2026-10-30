package com.example.store_scheduler_backend.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

// 관공서 공휴일과 대체공휴일. 음력 공휴일과 선거일은 해마다 달라 연도별로 적어 둔다 (새 연도는 추가 필요)
public final class KoreanHolidays {

    private static final Map<Integer, Map<LocalDate, String>> BY_YEAR = Map.of(
            2026, table(
                    "2026-01-01", "신정",
                    "2026-02-16", "설날 연휴", "2026-02-17", "설날", "2026-02-18", "설날 연휴",
                    "2026-03-01", "삼일절", "2026-03-02", "대체공휴일(삼일절)",
                    "2026-05-01", "노동절",
                    "2026-05-05", "어린이날",
                    "2026-05-24", "부처님오신날", "2026-05-25", "대체공휴일(부처님오신날)",
                    "2026-06-03", "전국동시지방선거",
                    "2026-06-06", "현충일",
                    "2026-07-17", "제헌절",
                    "2026-08-15", "광복절", "2026-08-17", "대체공휴일(광복절)",
                    "2026-09-24", "추석 연휴", "2026-09-25", "추석", "2026-09-26", "추석 연휴",
                    "2026-10-03", "개천절", "2026-10-05", "대체공휴일(개천절)",
                    "2026-10-09", "한글날",
                    "2026-12-25", "성탄절"),
            2027, table(
                    "2027-01-01", "신정",
                    "2027-02-06", "설날 연휴", "2027-02-07", "설날", "2027-02-08", "설날 연휴", "2027-02-09", "대체공휴일(설날)",
                    "2027-03-01", "삼일절",
                    "2027-05-01", "노동절",
                    "2027-05-05", "어린이날",
                    "2027-05-13", "부처님오신날",
                    "2027-06-06", "현충일",
                    "2027-07-17", "제헌절", "2027-07-19", "대체공휴일(제헌절)",
                    "2027-08-15", "광복절", "2027-08-16", "대체공휴일(광복절)",
                    "2027-09-14", "추석 연휴", "2027-09-15", "추석", "2027-09-16", "추석 연휴",
                    "2027-10-03", "개천절", "2027-10-04", "대체공휴일(개천절)",
                    "2027-10-09", "한글날", "2027-10-11", "대체공휴일(한글날)",
                    "2027-12-25", "성탄절", "2027-12-27", "대체공휴일(성탄절)"));

    private KoreanHolidays() {
    }

    public static Map<LocalDate, String> of(int year) {
        return BY_YEAR.getOrDefault(year, Map.of());
    }

    public static boolean isHoliday(LocalDate date) {
        return of(date.getYear()).containsKey(date);
    }

    private static Map<LocalDate, String> table(String... pairs) {
        Map<LocalDate, String> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(LocalDate.parse(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
