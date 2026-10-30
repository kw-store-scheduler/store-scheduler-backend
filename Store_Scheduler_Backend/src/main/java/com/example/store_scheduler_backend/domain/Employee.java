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

    // 마지막 근무일. 아직 APPROVED면 퇴사 예정, RESIGNED면 퇴사 완료
    private LocalDate resignedOn;

    // 정규직 직책. 알바는 null
    @Enumerated(EnumType.STRING)
    private StaffPosition position;

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

    // 수습 시급. 최저임금 감액 요건을 확인한 뒤 점주가 정하고, 수습 시작 3개월까지만 적용된다
    private Integer probationWage;

    // 예상 실수령액 계산용. null은 공제 없음
    @Enumerated(EnumType.STRING)
    private PayDeduction deduction;

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

    public void updatePaySettings(PayDeduction deduction, Integer probationWage) {
        this.deduction = deduction;
        this.probationWage = probationWage;
    }

    public PayDeduction getDeduction() {
        return deduction != null ? deduction : PayDeduction.NONE;
    }

    public boolean isProbationWageOn(LocalDate date) {
        return probationWage != null && startDate != null && probationUntil != null
                && !date.isBefore(startDate) && !date.isAfter(probationUntil) && !date.isAfter(startDate.plusMonths(3));
    }

    public void updatePosition(StaffPosition position) {
        this.position = position;
    }

    // 알바이고 입사(근무 시작) 1개월 이내
    public boolean isNewcomer(LocalDate today) {
        return getEmploymentType() == EmploymentType.PART_TIME && startDate != null && !today.isAfter(startDate.plusMonths(1));
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
        this.resignedOn = null;
        this.hourlyWage = hourlyWage;
        if (employmentType != null) this.employmentType = employmentType;
    }

    public EmploymentType getEmploymentType() {
        return employmentType != null ? employmentType : EmploymentType.PART_TIME;
    }

    public void reject() {
        this.status = EmployeeStatus.REJECTED;
    }

    public void scheduleResignation(LocalDate lastWorkDate) {
        this.resignedOn = lastWorkDate;
    }

    public void cancelScheduledResignation() {
        this.resignedOn = null;
    }

    // 퇴사 예정일 뒤의 날짜인지
    public boolean isGoneBy(LocalDate date) {
        return resignedOn != null && date.isAfter(resignedOn);
    }

    public void resign(LocalDate lastWorkDate) {
        this.status = EmployeeStatus.RESIGNED;
        this.resignedOn = lastWorkDate;
    }

    // 거절됐거나 퇴사한 직원의 재합류 신청. 근무·급여 이력이 이어지도록 같은 행을 쓴다
    public void reapply(String name, String phoneNumber) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.status = EmployeeStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
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