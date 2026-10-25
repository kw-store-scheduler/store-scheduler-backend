package com.example.store_scheduler_backend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 매장 공지. 점주가 쓰고 매장 구성원 모두 본다. 고정한 공지가 맨 위에 온다
@Entity
@Getter
@NoArgsConstructor
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    private boolean pinned;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Notice(Store store, String title, String content, boolean pinned) {
        this.store = store;
        this.title = title;
        this.content = content;
        this.pinned = pinned;
        this.createdAt = LocalDateTime.now();
    }
}
