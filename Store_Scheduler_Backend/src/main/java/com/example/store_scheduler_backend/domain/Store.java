package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(nullable = false, unique = true)
    private String storeCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    // true면 대신할 사람이 없어도 필수 휴무일에는 배정하지 않는다. 기존 매장 데이터 때문에 null 허용, null은 false로 본다
    private Boolean guaranteeMustDayOff;

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL)
    private List<Employee> employees = new ArrayList<>();

    public Store(String name, String address, User owner, String storeCode) {
        this.name = name;
        this.address = address;
        this.owner = owner;
        this.storeCode = storeCode;
    }

    public void reissueCode(String newCode) {
        this.storeCode = newCode;
    }

    public boolean isMustDayOffGuaranteed() {
        return Boolean.TRUE.equals(guaranteeMustDayOff);
    }

    public void updateSettings(boolean guaranteeMustDayOff) {
        this.guaranteeMustDayOff = guaranteeMustDayOff;
    }
}