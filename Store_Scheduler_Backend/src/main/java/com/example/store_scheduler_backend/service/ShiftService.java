package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.domain.Store;
import com.example.store_scheduler_backend.repository.ShiftRepository;
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
    private final StoreAccessGuard storeAccessGuard;

    @Transactional
    public Shift registerShift(Long storeId, String name, LocalTime startTime, LocalTime endTime,
                               int requiredStaff, DayOfWeek dayOfWeek, String managerUsername) {
        Store store = storeAccessGuard.verifyOwner(storeId, managerUsername);

        Shift shift = new Shift();
        shift.setName(name);
        shift.setStartTime(startTime);
        shift.setEndTime(endTime);
        shift.setRequiredStaff(requiredStaff);
        shift.setDayOfWeek(dayOfWeek);
        shift.setStore(store);
        return shiftRepository.save(shift);
    }

    public List<Shift> findByStore(Long storeId, String username) {
        storeAccessGuard.verifyMember(storeId, username);
        return shiftRepository.findByStoreId(storeId);
    }
}
