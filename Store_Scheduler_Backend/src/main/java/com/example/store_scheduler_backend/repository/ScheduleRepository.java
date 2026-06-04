package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    // 1번 매장의 스케줄을 날짜 오름차순, 시간 오름차순으로 정렬해서 가져와라
    List<Schedule> findByStoreIdOrderByWorkDateAscStartTimeAsc(Long storeId);
}