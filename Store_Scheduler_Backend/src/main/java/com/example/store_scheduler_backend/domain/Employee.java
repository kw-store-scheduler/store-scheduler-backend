package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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

    // 근로계약상 주 근무 시간 범위. 점주가 정한다
    private Integer contractMinWeeklyHours;

    private Integer contractMaxWeeklyHours;

    // 이 날짜까지 수습. 수습 중에는 수습이 아닌 직원과 같은 교대에만 배치된다
    private LocalDate probationUntil;

    // EmployeePermission 이름을 쉼표로 이어 저장
    private String permissions;

    // 약속 점수. 개인 사정으로 빠지고 대타를 못 구하면 -1, 매달 절반씩 회복. 휴무가 겹칠 때 순서를 정하는 데만 쓴다
    private Integer promiseScore;

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

    public void updateContract(Integer minWeeklyHours, Integer maxWeeklyHours) {
        this.contractMinWeeklyHours = minWeeklyHours;
        this.contractMaxWeeklyHours = maxWeeklyHours;
    }

    public boolean isOnProbation(LocalDate date) {
        return probationUntil != null && !date.isAfter(probationUntil);
    }

    public void updateProbation(LocalDate probationUntil) {
        this.probationUntil = probationUntil;
    }

    public List<EmployeePermission> getPermissionList() {
        if (permissions == null || permissions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(permissions.split(",")).map(EmployeePermission::valueOf).toList();
    }

    public void updatePermissions(Collection<EmployeePermission> permissions) {
        this.permissions = permissions.isEmpty() ? null
                : String.join(",", permissions.stream().distinct().sorted().map(Enum::name).toList());
    }

    public int getPromiseScore() {
        return promiseScore != null ? promiseScore : 0;
    }

    public void losePromisePoint() {
        this.promiseScore = getPromiseScore() - 1;
    }

    // 0을 향해 절반 (-3 → -1)
    public void recoverPromiseScore() {
        this.promiseScore = getPromiseScore() / 2;
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