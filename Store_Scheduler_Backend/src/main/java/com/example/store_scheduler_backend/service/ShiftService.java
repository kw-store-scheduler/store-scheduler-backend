package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import com.example.store_scheduler_backend.repository.StoreRepository;
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
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional
    public Shift registerShift(Long storeId, String name, LocalTime startTime, LocalTime endTime,
                               int requiredStaff, DayOfWeek dayOfWeek, String managerUsername) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 매장입니다."));

        if (!store.getOwner().getUsername().equals(managerUsername)) {
            throw new IllegalArgumentException("해당 매장의 관리자만 근무 시간대를 등록할 수 있습니다.");
        }

        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        shift.setRequiredStaff(requiredStaff);
        shift.setDayOfWeek(dayOfWeek);
        shift.setStore(store);
        return shiftRepository.save(shift);
    }

    public List<Shift> findByStore(Long storeId) {
        return shiftRepository.findByStoreId(storeId);
    }

    // ScheduleController 내부에서 사용
    public List<Shift> findShifts() {
        return shiftRepository.findAll();
    }
}
