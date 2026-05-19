package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Availability;
import com.example.store_scheduler_backend.repository.AvailabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AvailabilityService {

    private final AvailabilityRepository availabilityRepository;

    /**
     * 알바생 출근 가능(가용) 시간 등록
     */
    @Transactional
    public Long registerAvailability(Availability availability) {
        // 실무형 예외 처리: 시작 시간이 종료 시간보다 늦을 수 없습니다.
        if (availability.getStartTime() != null && availability.getEndTime() != null) {
            if (availability.getStartTime().isAfter(availability.getEndTime())) {
                throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
            }
        }
        availabilityRepository.save(availability);
        return availability.getId();
    }

    /**
     * 전체 가용 시간 목록 조회
     */
    public List<Availability> findAvailabilities() {
        return availabilityRepository.findAll();
    }
}