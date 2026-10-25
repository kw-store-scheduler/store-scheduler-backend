package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

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

    // 매장에서 부르는 이름. 없으면 name을 쓴다
    private String nickname;

    private LocalDate startDate;

    // 22시~06시 근무와 하루 7시간 넘는 교대에서 제외. 기존 데이터 때문에 null 허용, null은 false로 본다
    private Boolean minor;

    private LocalDateTime requestedAt;

    private Integer hourlyWage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmployeeStatus status = EmployeeStatus.PENDING;

    // 기존 데이터 때문에 null 허용, null은 알바로 본다
    @Enumerated(EnumType.STRING)
    private EmploymentType employmentType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToMany
    @JoinTable(
            name = "employee_skill",
            joinColumns = @JoinColumn(name = "employee_id"),
            inverseJoinColumns = @JoinColumn(name = "skill_id")
    )
    private Set<Skill> skills = new HashSet<>();

    public Employee(String name, String phoneNumber, Store store, User user) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.store = store;
        this.user = user;
        this.hourlyWage = 0;
        this.status = EmployeeStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
    }

    // 가입 화면에서 직원이 고른 고용 형태. 점주가 승인할 때 바꿀 수 있다
    public void fillJoinProfile(String nickname, LocalDate startDate, boolean minor, EmploymentType employmentType) {
        this.nickname = nickname;
        this.startDate = startDate;
        this.minor = minor;
        this.employmentType = employmentType;
    }

    public boolean isMinor() {
        return Boolean.TRUE.equals(minor);
    }

    public String getDisplayName() {
        return nickname != null && !nickname.isBlank() ? nickname : name;
    }

    public void approve(int hourlyWage) {
        approve(hourlyWage, null);
    }

    public void approve(int hourlyWage, EmploymentType employmentType) {
        this.status = EmployeeStatus.APPROVED;
        this.hourlyWage = hourlyWage;
        if (employmentType != null) this.employmentType = employmentType;
    }

    public EmploymentType getEmploymentType() {
        return employmentType != null ? employmentType : EmploymentType.PART_TIME;
    }

    public void reject() {
        this.status = EmployeeStatus.REJECTED;
    }

    public void updateHourlyWage(int hourlyWage) {
        this.hourlyWage = hourlyWage;
    }

    public void updateInfo(String name, String phoneNumber, Integer hourlyWage, EmploymentType employmentType) {
        if (name != null && !name.isBlank()) this.name = name;
        if (phoneNumber != null) this.phoneNumber = phoneNumber;
        if (hourlyWage != null) this.hourlyWage = hourlyWage;
        if (employmentType != null) this.employmentType = employmentType;
    }

    public void addSkill(Skill skill) {
        this.skills.add(skill);
    }

    public void removeSkill(Skill skill) {
        this.skills.remove(skill);
    }
}