package com.rbdip.bookstore.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rbdip.bookstore.BookstoreApplication;
import com.rbdip.bookstore.order.CreateOrderRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApiDuringNameMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void oneRunningApplicationServesReadsAndWritesAcrossBackfillAndContract() throws Exception {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "prepareThreshold=1";
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("2").load().migrate();
        seed(jdbc);
        String originalKey = jdbc.queryForObject("SELECT encode(contact_key, 'hex') FROM customers WHERE id=1", String.class);
        Flyway.configure().dataSource(source).target("3").load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE first_name IS NULL", Integer.class))
                .isEqualTo(2001);

        // The compatible version starts once after expand. Flyway runs outside
        // its lifecycle; neither backfill nor contract restarts this context.
        try (ServletWebServerApplicationContext app = startApplication(url);
                Traffic traffic = new Traffic(app.getWebServer().getPort())) {
            int port = app.getWebServer().getPort();
            traffic.start();
            traffic.awaitWindow();

            backfillWithContendedCustomer(source, jdbc, traffic);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE first_name IS NULL OR last_name IS NULL",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='customers'",
                    String.class)).contains("full_name");

            migrateWithTraffic(source, "5", traffic);
            migrateWithTraffic(source, "6", traffic);
            migrateWithTraffic(source, "7", traffic);
            traffic.nextWindow();
            traffic.awaitWindow();

            assertThat(app.isActive()).isTrue();
            assertThat(app.getWebServer().getPort()).isEqualTo(port);
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='customers'",
                    String.class)).doesNotContain("full_name");
            assertThat(jdbc.queryForObject("SELECT encode(contact_key, 'hex') FROM customers WHERE id=1", String.class))
                    .isEqualTo(originalKey);
        }
    }

    private ServletWebServerApplicationContext startApplication(String url) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BookstoreApplication.class).run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.flyway.enabled=false", "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.jpa.properties.hibernate.generate_statistics=false", "--logging.level.root=WARN");
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO customers(full_name,address) VALUES ('Before Migration','Address')");
        jdbc.update("INSERT INTO customers(full_name,address) SELECT 'Legacy ' || n, 'Address' FROM generate_series(1,2000) n");
        jdbc.update("INSERT INTO products(name,price) VALUES ('Book',10)");
        jdbc.update("INSERT INTO orders(customer_id,status) VALUES (1,'new')");
        jdbc.update("INSERT INTO order_items(order_id,product_id,quantity) VALUES (1,1,1)");
    }

    private void backfillWithContendedCustomer(PGSimpleDataSource source, JdbcTemplate jdbc, Traffic traffic)
            throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2); Connection sentinel = source.getConnection()) {
            sentinel.setAutoCommit(false);
            int sentinelPid;
            try (Statement statement = sentinel.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
                    result.next();
                    sentinelPid = result.getInt(1);
                }
                // Hold a late row so backfill retains locks on earlier rows.
                try (ResultSet ignored = statement.executeQuery("SELECT id FROM customers WHERE id=2001 FOR UPDATE")) {
                    assertThat(ignored.next()).isTrue();
                }
            }
            Future<?> migration = executor.submit(() -> migrateWithTraffic(source, "4", traffic));
            int migrationPid = awaitBlocked(jdbc, sentinelPid, "UPDATE customers%");
            CreateOrderRequest request = new CreateOrderRequest("Legacy 1", "Address", null, null, null,
                    List.of(new CreateOrderRequest.Item(1L, 1)));
            Future<ResponseEntity<Map>> order = executor.submit(() -> traffic.rest.postForEntity("/orders", request, Map.class));
            awaitBlocked(jdbc, migrationPid, "SELECT resolve_customer%");

            // Backfill may now commit. Taking an exclusive table lock before
            // releasing its row locks would deadlock with the waiting POST.
            sentinel.commit();
            migration.get(20, TimeUnit.SECONDS);
            assertThat(order.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    private int awaitBlocked(JdbcTemplate jdbc, int blocker, String queryPattern) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            List<Integer> pids = jdbc.queryForList("SELECT pid FROM pg_stat_activity"
                    + " WHERE ? = ANY(pg_blocking_pids(pid)) AND query LIKE ?", Integer.class, blocker, queryPattern);
            if (!pids.isEmpty()) {
                return pids.getFirst();
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Expected blocked transaction: " + queryPattern);
    }

    private void migrateWithTraffic(PGSimpleDataSource source, String version, Traffic traffic) {
        AtomicInteger checkpoints = new AtomicInteger();
        Callback callback = new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.BEFORE_EACH_MIGRATE;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                // Establish active HTTP readers AND writers inside each Flyway
                // invocation, then let them continue throughout the real DDL.
                traffic.nextWindow();
                traffic.awaitWindow();
                checkpoints.incrementAndGet();
            }

            @Override
            public String getCallbackName() {
                return "concurrent-api-traffic";
            }
        };
        Flyway.configure().dataSource(source).target(version).callbacks(callback).load().migrate();
        assertThat(checkpoints.get()).isEqualTo(1);
        assertThat(traffic.errors).isEmpty();
    }

    private static final class Traffic implements AutoCloseable {

        private record Window(CountDownLatch reads, CountDownLatch writes) {
            Window() {
                this(new CountDownLatch(4), new CountDownLatch(4));
            }
        }

        private final TestRestTemplate rest;
        private final ExecutorService workers = Executors.newFixedThreadPool(4);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Window> window = new AtomicReference<>(new Window());
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger writes = new AtomicInteger();

        Traffic(int port) {
            rest = new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port)
                    .setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(10)));
        }

        void start() {
            for (int i = 0; i < 4; i++) {
                int worker = i;
                workers.execute(() -> serve(worker));
            }
        }

        private void serve(int worker) {
            String name = worker % 2 == 0 ? "Before Migration" : "Traffic " + worker;
            CreateOrderRequest request = new CreateOrderRequest(name, "Address", null, null, null,
                    List.of(new CreateOrderRequest.Item(1L, 1)));
            try {
                while (running.get()) {
                    Window active = window.get();
                    ResponseEntity<List> listed = rest.getForEntity("/orders", List.class);
                    if (listed.getStatusCode() != HttpStatus.OK || listed.getBody() == null
                            || listed.getBody().stream().noneMatch(row -> row instanceof Map<?, ?> order
                                    && "Before Migration".equals(order.get("customerFullName")))) {
                        throw new IllegalStateException("GET /orders returned invalid status or legacy name: " + listed.getStatusCode());
                    }
                    reads.incrementAndGet();
                    active.reads().countDown();
                    active = window.get();
                    ResponseEntity<Map> created = rest.postForEntity("/orders", request, Map.class);
                    if (created.getStatusCode() != HttpStatus.CREATED || created.getBody() == null
                            || created.getBody().get("id") == null || !"new".equals(created.getBody().get("status"))) {
                        throw new IllegalStateException("POST /orders failed: " + created.getStatusCode() + " " + created.getBody());
                    }
                    writes.incrementAndGet();
                    active.writes().countDown();
                }
            } catch (Exception failure) {
                errors.add(failure.toString());
                running.set(false);
            }
        }

        void nextWindow() {
            window.set(new Window());
        }

        void awaitWindow() {
            Window active = window.get();
            try {
                assertThat(active.reads().await(15, TimeUnit.SECONDS)).as("Concurrent GETs: %s", errors).isTrue();
                assertThat(active.writes().await(15, TimeUnit.SECONDS)).as("Concurrent POSTs: %s", errors).isTrue();
                assertThat(errors).isEmpty();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        @Override
        public void close() {
            running.set(false);
            workers.shutdown();
            try {
                if (!workers.awaitTermination(15, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                    assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                }
            } catch (InterruptedException interrupted) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            System.out.printf("Migration HTTP traffic: GET=%d POST=%d errors=%d%n", reads.get(), writes.get(), errors.size());
            assertThat(errors).isEmpty();
        }
    }
}
