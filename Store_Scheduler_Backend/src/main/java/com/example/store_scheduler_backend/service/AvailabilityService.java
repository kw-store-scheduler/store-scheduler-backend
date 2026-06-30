package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.User;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import com.example.store_scheduler_backend.repository.EmployeeRepository;
import com.example.store_scheduler_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AvailabilityService {

    private final AvailabilityRepository availabilityRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;

    @Transactional
    public Availability register(Long storeId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String username) {
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Employee employee = employeeRepository.findByStoreIdAndUserId(storeId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 승인된 직원이 아닙니다."));

        if (employee.getStatus() != EmployeeStatus.APPROVED) {
            throw new IllegalArgumentException("승인된 직원만 가용 시간을 등록할 수 있습니다.");
        }

        // 같은 요일 중복 등록 방지
        availabilityRepository.findByEmployeeIdAndDayOfWeek(employee.getId(), dayOfWeek).ifPresent(a -> {
            throw new IllegalArgumentException("해당 요일의 가용 시간이 이미 등록되어 있습니다.");
        });

        return availabilityRepository.save(new Availability(employee, dayOfWeek, startTime, endTime));
    }

    public List<Availability> findMyAvailabilities(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        // 해당 유저가 속한 모든 매장의 가용 시간 반환
        List<Employee> employees = employeeRepository.findByUserId(user.getId());
        return employees.stream()
                .flatMap(e -> availabilityRepository.findByEmployeeId(e.getId()).stream())
                .toList();
    }

    public List<Availability> findByStore(Long storeId) {
        return availabilityRepository.findByEmployee_StoreId(storeId);
    }

    // ScheduleAutomationService에서 사용
    public List<Availability> findAvailabilities() {
        return availabilityRepository.findAll();
    }

    @Transactional
    public Availability update(Long availabilityId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, String username) {
        if (startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
        }

        Availability availability = availabilityRepository.findById(availabilityId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 가용 시간입니다."));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (!availability.getEmployee().getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("본인의 가용 시간만 수정할 수 있습니다.");
        }

        // 같은 요일로 변경 시 중복 체크 (본인 것은 제외)
        availabilityRepository.findByEmployeeIdAndDayOfWeek(availability.getEmployee().getId(), dayOfWeek)
                .filter(a -> !a.getId().equals(availabilityId))
                .ifPresent(a -> { throw new IllegalArgumentException("해당 요일의 가용 시간이 이미 등록되어 있습니다."); });

        availability.update(dayOfWeek, startTime, endTime);
        return availability;
    }

    @Transactional
    public void delete(Long availabilityId, String username) {
        Availability availability = availabilityRepository.findById(availabilityId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 가용 시간입니다."));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (!availability.getEmployee().getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("본인의 가용 시간만 삭제할 수 있습니다.");
        }

        availabilityRepository.delete(availability);
    }
}
