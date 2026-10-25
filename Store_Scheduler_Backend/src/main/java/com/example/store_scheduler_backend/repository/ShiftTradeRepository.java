package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.ShiftTrade;
import com.example.store_scheduler_backend.domain.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ShiftTradeRepository extends JpaRepository<ShiftTrade, Long> {
    List<ShiftTrade> findByStoreIdAndStatusInOrderByCreatedAtDesc(Long storeId, Collection<TradeStatus> statuses);
    List<ShiftTrade> findByStoreIdAndRequesterIdOrStoreIdAndTargetIdOrderByCreatedAtDesc(Long storeId, Long requesterId,
                                                                                          Long storeId2, Long targetId);
    List<ShiftTrade> findByRequesterSchedule_IdInOrTargetSchedule_IdIn(Collection<Long> requesterScheduleIds,
                                                                       Collection<Long> targetScheduleIds);
}
