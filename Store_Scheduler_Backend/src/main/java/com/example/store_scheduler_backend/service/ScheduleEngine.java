package com.example.store_scheduler_backend.service;

import java.util.Map;

// 최적화 엔진 호출. scheduler.engine.url이 있으면 엔진 서버(HTTP), 없으면 같은 서버의 파이썬 프로세스로 실행한다
public interface ScheduleEngine {

    Map<String, Object> run(Map<String, Object> config);
}
