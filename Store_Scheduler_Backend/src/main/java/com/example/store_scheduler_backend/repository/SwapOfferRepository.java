package com.example.store_scheduler_backend.repository;

import com.example.store_scheduler_backend.domain.OfferStatus;
import com.example.store_scheduler_backend.domain.SwapOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SwapOfferRepository extends JpaRepository<SwapOffer, Long> {
    List<SwapOffer> findBySwapRequestIdOrderBySentAtAsc(Long swapRequestId);
    Optional<SwapOffer> findBySwapRequestIdAndCandidateId(Long swapRequestId, Long candidateId);
    List<SwapOffer> findByCandidateIdAndStatusOrderBySentAtDesc(Long candidateId, OfferStatus status);
    List<SwapOffer> findByStatusAndSentAtBefore(OfferStatus status, LocalDateTime before);
}
