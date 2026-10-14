package ru.test.ydbcluster;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Простое тестовое приложение для проверки работы с кластером YDB через JDBC.
 *
 * <p>Подключается к базе из переменной окружения YDB_CONNECTION_STRING
 * (по умолчанию jdbc:ydb:grpc://ydb-node-1:2136/local/testdb),
 * создаёт таблицу series, пишет в неё несколько строк и читает их обратно.</p>
 *
 * <p>Весь сценарий выполняется в цикле с повторами: кластеру и динамической
 * ноде нужно время на инициализацию при первом подъёме. Все операции
 * идемпотентны (CREATE TABLE IF NOT EXISTS, UPSERT), поэтому повтор
 * безопасен.</p>
 */
public class App {

    private static final int MAX_ATTEMPTS = 20;
    private static final long RETRY_DELAY_MS = 5000;

    public static void main(String[] args) throws Exception {
        // Регистрируем драйвер явно — не полагаемся на механизм ServiceLoader.
        Class.forName("tech.ydb.jdbc.YdbDriver");

        String url = System.getenv().getOrDefault(
                "YDB_CONNECTION_STRING",
                "jdbc:ydb:grpc://ydb-node-1:2136/local/testdb");
        System.out.println("Connecting to YDB: " + url);

        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Connection conn = DriverManager.getConnection(url)) {
                createTable(conn);
                upsertRows(conn);
                selectRows(conn);
                System.out.println("SUCCESS");
                return;
            } catch (SQLException e) {
                last = e;
                System.out.printf("Attempt %d/%d failed: %s%n",
                        attempt, MAX_ATTEMPTS, e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while waiting to retry", ie);
                }
            }
        }
        throw last;
    }

    private static void createTable(Connection conn) throws SQLException {
        String ddl = "CREATE TABLE IF NOT EXISTS series ("
                + "series_id UInt64, "
                + "title Text, "
                + "PRIMARY KEY (series_id)"
                + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
        System.out.println("Table 'series' is ready.");
    }

    private static void upsertRows(Connection conn) throws SQLException {
        String dml = "UPSERT INTO series (series_id, title) VALUES "
                + "(1, \"IT Crowd\"), "
                + "(2, \"Silicon Valley\"), "
                + "(3, \"Mr. Robot\")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(dml);
        }
        System.out.println("3 rows upserted.");
    }

    private static void selectRows(Connection conn) throws SQLException {
        String query = "SELECT series_id, title FROM series ORDER BY series_id";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.println("Rows in 'series':");
            while (rs.next()) {
                System.out.printf("  series_id=%d  title=%s%n",
                        rs.getLong("series_id"), rs.getString("title"));
            }
        }
    }
}
