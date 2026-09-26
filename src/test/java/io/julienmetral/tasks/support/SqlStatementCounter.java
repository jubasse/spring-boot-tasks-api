package io.julienmetral.tasks.support;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Records the SQL that Hibernate prepares on the calling thread while {@link #statementsDuring} runs. Hibernate
 * creates the instance itself: {@code hibernate.session_factory.statement_inspector} in the test
 * {@code application.yaml} registers it for every test context, so using it creates no new context. JDBC that
 * bypasses Hibernate ({@code JdbcTemplate}) is not recorded.
 */
public class SqlStatementCounter implements StatementInspector {

    // Per thread: MockMvc handles a request on the test's thread, while the outbox relay, the RabbitMQ listeners and
    // the scheduled jobs query the database on their own threads at any time
    private static final ThreadLocal<List<String>> RECORDING = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        List<String> recording = RECORDING.get();

        if (recording != null) {
            recording.add(sql);
        }

        return sql;
    }

    public static List<String> statementsDuring(Action action) throws Exception {
        List<String> recording = new ArrayList<>();

        RECORDING.set(recording);
        try {
            action.run();
        } finally {
            RECORDING.remove();
        }

        return List.copyOf(recording);
    }

    @FunctionalInterface
    public interface Action {

        void run() throws Exception;
    }
}
