package com.macrodash.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 백엔드가 쓰는 테이블이 DB에 있는지(ADR 0008).
 *
 * <p>스키마 파일은 수집기가 기동할 때 적용합니다. 백엔드가 먼저 떠 있으면 새 테이블이 아직 없을 수 있어,
 * 그때는 기동을 멈추지 않고 그 기능만 "준비 중"으로 둡니다 — 수집기가 내려가 있어도 나머지 화면은
 * 저장본으로 떠야 하기 때문입니다. 한 번 있다고 확인한 테이블은 다시 묻지 않습니다(테이블은 지워지지 않음).
 */
@Component
public class SchemaCheck {

    private final JdbcTemplate jdbc;
    private final Set<String> present = ConcurrentHashMap.newKeySet();
    /** 있다고 확인한 열("테이블\0열"). 열도 지워지지 않는다고 봅니다. */
    private final Set<String> presentColumns = ConcurrentHashMap.newKeySet();

    public SchemaCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 테이블에 그 열이 있는가 — 기존 테이블에 열을 더하는 스키마 파일(예: V9)이 적용됐는지 봅니다. 테이블이 없으면 false.
     */
    public boolean columnExists(String table, String column) {
        String key = table + '\0' + column;
        if (presentColumns.contains(key)) {
            return true;
        }
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass(?) "
                + "AND attname = ? AND attnum > 0 AND NOT attisdropped)", Boolean.class, table, column);
        if (Boolean.TRUE.equals(exists)) {
            presentColumns.add(key);
            return true;
        }
        return false;
    }

    public boolean tableExists(String table) {
        if (present.contains(table)) {
            return true;
        }
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        if (Boolean.TRUE.equals(exists)) {
            present.add(table);
            return true;
        }
        return false;
    }
}
