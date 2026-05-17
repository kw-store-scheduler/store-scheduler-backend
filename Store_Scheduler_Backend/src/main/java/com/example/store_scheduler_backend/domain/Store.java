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
    private String name; // 매장 이름
    private String address; // 매장 주소

    // 매장 하나에 여러 직원이 속함 -> 1:N 관계
    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL)
    private List<Employee> employees = new ArrayList<>();
}
