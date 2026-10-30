package com.example.store_scheduler_backend;

import com.example.store_scheduler_backend.domain.*;
import com.example.store_scheduler_backend.repository.*;
import com.example.store_scheduler_backend.service.SwapRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 동시 요청을 재현해야 해서 @Transactional 없이 스레드마다 트랜잭션을 나눈다
@SpringBootTest
class ConcurrentAcceptTest {

    @Autowired UserRepository userRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired AvailabilityRepository availabilityRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SwapRequestRepository swapRequestRepository;
    @Autowired SwapOfferRepository offerRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired SwapRequestService swapRequestService;

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll();
        offerRepository.deleteAll();
        swapRequestRepository.deleteAll();
        scheduleRepository.deleteAll();
        availabilityRepository.deleteAll();
        employeeRepository.deleteAll();
        storeRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 사장님이_보낸_대타_요청을_둘이_동시에_수락해도_한_명만_확정된다() throws Exception {
        Store store = storeRepository.save(new Store("동시", null, userRepository.save(new User("owner", "pw", Role.MANAGER)), "CONCUR01"));
        Employee requester = employee(store, "requester");
        Employee first = employee(store, "first");
        Employee second = employee(store, "second");
        Schedule shift = new Schedule();
        shift.setStore(store);
        shift.setEmployee(requester);
        shift.setWorkDate(LocalDate.now().plusDays(7));
        shift.setStartTime(LocalTime.of(9, 0));
        shift.setEndTime(LocalTime.of(13, 0));
        scheduleRepository.save(shift);
        Long storeId = store.getId();
        Long requestId = swapRequestService.request(storeId, shift.getId(), "사정", "requester").getId();
        swapRequestService.sendOffers(storeId, requestId, List.of(first.getId(), second.getId()), "owner");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> results = List.of(
                pool.submit(accept(start, storeId, requestId, "first")),
                pool.submit(accept(start, storeId, requestId, "second")));
        start.countDown();
        pool.shutdown();

        long succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(swapRequestRepository.findById(requestId).orElseThrow().getStatus()).isEqualTo(SwapStatus.ACCEPTED);
    }

    private Callable<Boolean> accept(CountDownLatch start, Long storeId, Long requestId, String username) {
        return () -> {
            start.await();
            try {
                swapRequestService.accept(storeId, requestId, username);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        };
    }

    private Employee employee(Store store, String name) {
        Employee e = new Employee(name, null, store, userRepository.save(new User(name, "pw", Role.EMPLOYEE)));
        e.approve(10030);
        employeeRepository.save(e);
        for (DayOfWeek d : DayOfWeek.values()) {
            availabilityRepository.save(new Availability(e, d, LocalTime.MIN, LocalTime.of(23, 59)));
        }
        return e;
    }
}
