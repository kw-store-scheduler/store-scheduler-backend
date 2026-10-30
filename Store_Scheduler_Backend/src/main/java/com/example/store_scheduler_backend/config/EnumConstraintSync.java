package com.example.store_scheduler_backend.config;

import com.example.store_scheduler_backend.domain.EmployeeStatus;
import com.example.store_scheduler_backend.domain.LeaveStatus;
import com.example.store_scheduler_backend.domain.SwapStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// ddl-auto: update는 이미 만들어진 enum CHECK 제약을 고치지 않아, 기존 enum에 값을 추가하면 운영 DB에서 저장이 실패한다
@Slf4j
@Component
@RequiredArgsConstructor
public class EnumConstraintSync implements ApplicationRunner {

    private record EnumColumn(String table, String column, Class<? extends Enum<?>> type) {
    }

    private static final List<EnumColumn> COLUMNS = List.of(
            new EnumColumn("swap_request", "status", SwapStatus.class),
            new EnumColumn("employee", "status", EmployeeStatus.class),
            new EnumColumn("leave_request", "status", LeaveStatus.class));

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }
        COLUMNS.forEach(this::sync);
    }

    private void sync(EnumColumn target) {
        String name = target.table() + "_" + target.column() + "_check";
        List<String> values = Arrays.stream(target.type().getEnumConstants()).map(Enum::name).toList();
        try {
            List<String> current = jdbcTemplate.queryForList(
                    "select pg_get_constraintdef(oid) from pg_constraint where conname = ? and conrelid = ?::regclass",
                    String.class, name, target.table());
            if (!current.isEmpty() && values.stream().allMatch(v -> current.get(0).contains("'" + v + "'"))) {
                return;
            }
            String allowed = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
            jdbcTemplate.execute("alter table " + target.table() + " drop constraint if exists " + name
                    + ", add constraint " + name + " check (" + target.column() + " in (" + allowed + "))");
            log.info("enum 제약 갱신: {} {}", name, values);
        } catch (DataAccessException e) {
            log.error("enum 제약 갱신 실패: {}", name, e);
        }
    }
}
