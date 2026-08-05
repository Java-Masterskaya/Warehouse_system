package com.warehouse.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures SQL issued on the current test thread.
 */
public final class SqlCaptureStatementInspector implements StatementInspector {

    private static final ThreadLocal<List<String>> STATEMENTS =
            ThreadLocal.withInitial(ArrayList::new);

    @Override
    public String inspect(String sql) {
        STATEMENTS.get().add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.get().clear();
    }

    public static List<String> statements() {
        return List.copyOf(STATEMENTS.get());
    }
}
