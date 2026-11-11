package com.rbdip.bookstore.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Гейт ЛР3: разбиение customers.full_name на first_name/last_name без
 * даунтайма (паттерн expand-contract). Тест сознательно НЕ использует
 * Spring-контекст (Hibernate в режиме ddl-auto=validate не переживёт
 * промежуточное состояние схемы) - вместо этого он сам управляет накатом
 * миграций через Flyway API и параллельно шлёт "старые" запросы по JDBC.
 *
 * <p>Конвенция для студентов (см. README в src/main/resources/db/migration):
 * <ul>
 *   <li>миграции ЛР3 содержат {@code lab3} в описании, например
 *       {@code V7__lab3_expand_add_name_columns.sql};</li>
 *   <li>их должно быть не меньше двух; последняя из них - "contract"-шаг
 *       (удаление full_name), все предыдущие - expand/backfill;</li>
 *   <li>все миграции ЛР2 (нормализация, таблица customers с колонкой
 *       full_name) идут раньше миграций ЛР3.</li>
 * </ul>
 *
 * <p>Тест проверяет: (1) expand/backfill-шаги не ломают чтения старой
 * колонки под нагрузкой; (2) после expand новые колонки есть и
 * заполнены для уже существующих клиентов; (3) после contract старой
 * колонки нет. Без миграций ЛР3 тест красный - гейт засчитывается
 * только за выполненную работу.
 */
@Testcontainers
class LoadDuringMigrationReferenceTest {

    private static final int READERS = 4;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bookstore_migration")
            .withUsername("bookstore")
            .withPassword("bookstore");

    @Test
    void nameSplitFollowsExpandContractWithoutBreakingOldReaders() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        MigrationInfo[] all = flyway(dataSource, null).info().pending();
        List<MigrationInfo> lab3 = Arrays.stream(all)
                .filter(m -> m.getDescription().toLowerCase(Locale.ROOT).contains("lab3"))
                .toList();

        assertThat(lab3)
                .as("Нужно минимум две миграции ЛР3 с 'lab3' в описании: expand(/backfill) и contract")
                .hasSizeGreaterThanOrEqualTo(2);

        int firstLab3 = Arrays.asList(all).indexOf(lab3.get(0));
        assertThat(firstLab3)
                .as("Перед миграциями ЛР3 должны идти V1 и миграции ЛР2")
                .isGreaterThan(0);
        String beforeLab3 = all[firstLab3 - 1].getVersion().getVersion();
        String lastBeforeContract = all[all.length - 2].getVersion().getVersion();
        assertThat(all[all.length - 1])
                .as("Последней в цепочке должна быть contract-миграция ЛР3")
                .isEqualTo(lab3.get(lab3.size() - 1));

        // Состояние "после ЛР2": таблица customers с колонкой full_name.
        flyway(dataSource, beforeLab3).migrate();
        assertThat(customerColumns(jdbc))
                .as("К началу ЛР3 в customers должна быть колонка full_name (результат ЛР2)")
                .contains("full_name");
        boolean seeded = trySeedCustomer(jdbc);

        List<Exception> errors = new CopyOnWriteArrayList<>();
        AtomicBoolean keepReading = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(READERS);
        ExecutorService pool = Executors.newFixedThreadPool(READERS);

        Runnable reader = () -> {
            started.countDown();
            while (keepReading.get()) {
                try (Connection c = dataSource.getConnection();
                        Statement st = c.createStatement()) {
                    st.executeQuery("SELECT full_name FROM customers LIMIT 1");
                    reads.incrementAndGet();
                } catch (SQLException e) {
                    errors.add(e);
                }
            }
        };
        for (int i = 0; i < READERS; i++) {
            pool.submit(reader);
        }
        started.await(5, TimeUnit.SECONDS);

        // Все expand/backfill-миграции накатываются, пока фоновые потоки
        // продолжают читать старую колонку.
        flyway(dataSource, lastBeforeContract).migrate();

        keepReading.set(false);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(errors)
                .as("Чтения customers.full_name не должны падать во время expand/backfill-миграций")
                .isEmpty();
        assertThat(reads.get()).as("Фоновые чтения должны были реально выполняться").isPositive();
        assertThat(customerColumns(jdbc))
                .as("После expand-шагов в customers должны быть first_name и last_name")
                .contains("first_name", "last_name");
        if (seeded) {
            Integer notBackfilled = jdbc.queryForObject(
                    "SELECT count(*) FROM customers WHERE full_name IS NOT NULL"
                            + " AND (first_name IS NULL OR last_name IS NULL)",
                    Integer.class);
            assertThat(notBackfilled)
                    .as("Backfill: у существующих клиентов first_name/last_name должны быть заполнены")
                    .isZero();
        }

        flyway(dataSource, null).migrate();
        assertThat(customerColumns(jdbc))
                .as("После contract-шага старой колонки full_name быть не должно")
                .doesNotContain("full_name")
                .contains("first_name", "last_name");
    }

    private static Flyway flyway(PGSimpleDataSource dataSource, String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static List<String> customerColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'customers'",
                String.class);
    }

    /**
     * Остальные колонки customers студент проектирует сам, поэтому вставка
     * делается "по возможности": если схема требует других NOT NULL-полей,
     * проверка backfill пропускается.
     */
    private static boolean trySeedCustomer(JdbcTemplate jdbc) {
        try {
            jdbc.update("INSERT INTO customers (full_name) VALUES ('Иванов Иван')");
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }
}
