package com.example.store_scheduler_backend.service;

import com.example.store_scheduler_backend.domain.Employee;
import com.example.store_scheduler_backend.domain.OfferStatus;
import com.example.store_scheduler_backend.domain.Schedule;
import com.example.store_scheduler_backend.domain.SwapRequest;
import com.example.store_scheduler_backend.repository.ScheduleRepository;
import com.example.store_scheduler_backend.repository.SwapOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 대타 확정. 대타 요청 수락, 사장님 직접 배정, 게시판 지원 승인이 모두 여기로 모인다
@Component
@RequiredArgsConstructor
public class SwapAssignment {

    private final ScheduleRepository scheduleRepository;
    private final SwapOfferRepository offerRepository;
    private final FcmService fcmService;

    public void assign(SwapRequest request, Employee substitute) {
        Schedule cover = request.assign(substitute);
        if (cover != null) {
            scheduleRepository.save(cover);
        }
        closePendingOffers(request);
        String message = request.getWorkDate() + " " + request.getStartTime() + " 근무를 "
                + substitute.getDisplayName() + "님이 대신하기로 했습니다.";
        fcmService.sendToDevice(request.getRequester().getUser().getDeviceToken(), "대타 확정", message);
        fcmService.sendToDevice(request.getStore().getOwner().getDeviceToken(), "대타 확정", message);
        fcmService.sendToDevice(substitute.getUser().getDeviceToken(), "대타 확정",
                request.getWorkDate() + " " + request.getStartTime() + "~" + request.getEndTime() + " 근무가 내 근무표에 추가되었습니다.");
    }

    public void closePendingOffers(SwapRequest request) {
        offerRepository.findBySwapRequestIdOrderBySentAtAsc(request.getId()).stream()
                .filter(o -> o.isPending())
                .forEach(o -> o.respond(OfferStatus.CLOSED));
    }
}
