package com.example.store_scheduler_backend.service;

// 점주가 force로 진행할 수 있는 근무 규칙 위반. 응답에 forceable: true가 붙는다
public class ForceableRuleException extends IllegalArgumentException {

    public ForceableRuleException(String message) {
        super(message);
    }
}
