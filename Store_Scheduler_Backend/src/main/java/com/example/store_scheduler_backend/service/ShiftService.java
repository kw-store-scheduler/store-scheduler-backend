package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Shift;
import com.example.store_scheduler_backend.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;

    @Transactional
    public Long registerShift(Shift shift) {
        shiftRepository.save(shift);
        return shift.getId();
    }

    public List<Shift> findShifts() {
        return shiftRepository.findAll();
    }
}