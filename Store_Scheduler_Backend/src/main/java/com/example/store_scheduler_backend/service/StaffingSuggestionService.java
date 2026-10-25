package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.ClosingReport;
import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.ClosingReportRepository;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 최근 4주 마감 보고(요일·교대별 평균 객수)로 다음 달 교대별 필요 인원을 제안한다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffingSuggestionService {

    private static final int BASE_WEEKS = 4;

    private final StoreAccessGuard storeAccessGuard;
    private final ShiftRepository shiftRepository;
    private final ClosingReportRepository closingReportRepository;

    // averageCustomers·suggestedStaff는 기간 안에 보고가 없으면 null
    public record Suggestion(Long shiftId, String shiftName, DayOfWeek dayOfWeek, int sampleDays, Double averageCustomers,
                             int currentRequiredStaff, Integer suggestedStaff, String reason) {
    }

    public record Result(YearMonth month, LocalDate basedOnFrom, LocalDate basedOnTo, int customersPerStaff,
                         List<Suggestion> suggestions) {
    }

    // 같은 날짜·교대에 다시 보고하면 덮어쓴다
    @Transactional
    public ClosingReport report(Long storeId, Long shiftId, LocalDate date, int customerCount, Long sales, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        Shift shift = shiftRepository.findById(shiftId)
                .filter(s -> s.getStore().getId().equals(storeId))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 근무 시간대가 아닙니다."));
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("미래 날짜의 마감 보고는 등록할 수 없습니다.");
        }
        return closingReportRepository.findByShiftIdAndDate(shiftId, date)
                .map(existing -> {
                    existing.update(customerCount, sales);
                    return existing;
                })
                .orElseGet(() -> closingReportRepository.save(new ClosingReport(shift, date, customerCount, sales)));
    }

    public List<ClosingReport> findReports(Long storeId, LocalDate from, LocalDate to, String managerUsername) {
        storeAccessGuard.verifyOwner(storeId, managerUsername);
        return closingReportRepository.findByShift_Store_IdAndDateBetweenOrderByDateAsc(storeId, from, to);
    }

    public Result suggest(Long storeId, YearMonth month, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);
        int customersPerStaff = store.getCustomersPerStaffOrDefault();
        LocalDate to = month.atDay(1).minusDays(1);
        LocalDate from = to.minusWeeks(BASE_WEEKS).plusDays(1);

        Map<String, List<ClosingReport>> reportsBySlot = closingReportRepository
                .findByShift_Store_IdAndDateBetweenOrderByDateAsc(storeId, from, to).stream()
                .collect(Collectors.groupingBy(r -> r.getShift().getId() + "|" + r.getDate().getDayOfWeek()));

        List<Suggestion> suggestions = new ArrayList<>();
        List<Shift> shifts = shiftRepository.findByStoreId(storeId).stream()
                .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId))
                .toList();
        for (Shift shift : shifts) {
            for (DayOfWeek day : DayOfWeek.values()) {
                if (shift.getDayOfWeek() != null && shift.getDayOfWeek() != day) {
                    continue;
                }
                List<ClosingReport> samples = reportsBySlot.getOrDefault(shift.getId() + "|" + day, List.of());
                suggestions.add(toSuggestion(shift, day, samples, customersPerStaff));
            }
        }
        return new Result(month, from, to, customersPerStaff, suggestions);
    }

    private Suggestion toSuggestion(Shift shift, DayOfWeek day, List<ClosingReport> samples, int customersPerStaff) {
        int current = shift.getRequiredStaff() != null ? shift.getRequiredStaff() : 0;
        if (samples.isEmpty()) {
            return new Suggestion(shift.getId(), shift.getName(), day, 0, null, current, null, "최근 4주 마감 보고 없음");
        }
        double average = samples.stream().mapToInt(ClosingReport::getCustomerCount).average().orElse(0);
        int suggested = Math.max(1, (int) Math.ceil(average / customersPerStaff));
        String reason = "평균 객수 " + Math.round(average) + "명 ÷ 1인당 " + customersPerStaff + "명";
        if (suggested > current) {
            reason += " → 현재보다 " + (suggested - current) + "명 증원 권장";
        } else if (suggested < current) {
            reason += " → 현재보다 " + (current - suggested) + "명 감원 가능";
        } else {
            reason += " → 현재 인원 유지";
        }
        return new Suggestion(shift.getId(), shift.getName(), day, samples.size(),
                Math.round(average * 10) / 10.0, current, suggested, reason);
    }
}
