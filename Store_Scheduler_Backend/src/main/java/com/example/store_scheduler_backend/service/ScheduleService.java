package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;

    public List<Schedule> findByStore(Long storeId) {
        return scheduleRepository.findByStoreIdOrderByWorkDateAscStartTimeAsc(storeId);
    }

    public List<Schedule> findMySchedules(Long storeId, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Employee employee = employeeRepository.findByStoreIdAndUserId(storeId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 직원이 아닙니다."));

        if (employee.getStatus() != EmployeeStatus.APPROVED) {
            throw new IllegalArgumentException("승인된 직원만 근무표를 조회할 수 있습니다.");
        }

        return scheduleRepository.findByStoreIdAndEmployeeIdOrderByWorkDateAscStartTimeAsc(storeId, employee.getId());
    }

    @Transactional
    public Schedule update(Long storeId, Long scheduleId, LocalDate workDate, LocalTime startTime, LocalTime endTime, String managerUsername) {
        verifyOwner(storeId, managerUsername);

        Schedule schedule = findSchedule(scheduleId, storeId);
        schedule.setWorkDate(workDate);
        schedule.setStartTime(startTime);
        schedule.setEndTime(endTime);
        return schedule;
    }

    @Transactional
    public void delete(Long storeId, Long scheduleId, String managerUsername) {
        verifyOwner(storeId, managerUsername);
        Schedule schedule = findSchedule(scheduleId, storeId);
        scheduleRepository.delete(schedule);
    }

    private Schedule findSchedule(Long scheduleId, Long storeId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 스케줄입니다."));
        if (!schedule.getStore().getId().equals(storeId)) {
            throw new IllegalArgumentException("해당 매장의 스케줄이 아닙니다.");
        }
        return schedule;
    }

    private void verifyOwner(Long storeId, String username) {
        storeRepository.findById(storeId)
                .filter(s -> s.getOwner().getUsername().equals(username))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 관리자만 접근할 수 있습니다."));
    }
}
