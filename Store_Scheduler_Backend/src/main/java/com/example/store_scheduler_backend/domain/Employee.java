package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String phoneNumber;

    private Integer hourlyWage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmployeeStatus status = EmployeeStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public Employee(String name, String phoneNumber, Store store, User user) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.store = store;
        this.user = user;
        this.hourlyWage = 0;
        this.status = EmployeeStatus.PENDING;
    }

    public void approve(int hourlyWage) {
        this.status = EmployeeStatus.APPROVED;
        this.hourlyWage = hourlyWage;
    }

    public void reject() {
        this.status = EmployeeStatus.REJECTED;
    }

    public void updateHourlyWage(int hourlyWage) {
        this.hourlyWage = hourlyWage;
    }

    public void updateInfo(String name, String phoneNumber, Integer hourlyWage) {
        if (name != null && !name.isBlank()) this.name = name;
        if (phoneNumber != null) this.phoneNumber = phoneNumber;
        if (hourlyWage != null) this.hourlyWage = hourlyWage;
    }
}