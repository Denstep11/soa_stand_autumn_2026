package edu.soa.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.soa.orders.dto.*;
import edu.soa.orders.repository.OrderRepository;
import edu.soa.orders.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = "orders.workers-enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DATABASE_URL", db::getJdbcUrl);
    r.add("spring.datasource.username", db::getUsername);
    r.add("spring.datasource.password", db::getPassword);
  }

  @Autowired OrderRepository repo;
  @Autowired OrderService orders;
  @Autowired EventService events;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;

  @BeforeEach
  void clear() {
    jdbc.execute("TRUNCATE outbox,orders,delivery_inbox,bonuses");
  }

  UUID create() {
    return orders.create(new OrderRequest("u", List.of(new OrderRequest.Item("sku", 2, 50L))));
  }

  @Test
  void apiAndValidation() throws Exception {
    mvc.perform(
            post("/orders")
                .contentType("application/json")
                .content(
                    """
                    {"user_id":"u","items":[{"sku":"sku","qty":2,"price_cents":50}]}
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("pending"));
    mvc.perform(
            post("/orders")
                .contentType("application/json")
                .content(
                    """
                    {"user_id":"u","items":[{"sku":"sku","qty":0,"price_cents":50}]}
                    """))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/orders/not-a-uuid")).andExpect(status().isNotFound());
    mvc.perform(get("/users/nobody/bonus")).andExpect(jsonPath("$.points").value(0));
    mvc.perform(get("/healthz")).andExpect(status().isOk());
  }

  @Test
  void atomicClaimsAndFencingAfterRestart() throws Exception {
    UUID id = create();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 8; i++) tasks.add(() -> repo.claim().isPresent());
      int claimed = 0;
      for (var f : pool.invokeAll(tasks)) if (f.get()) claimed++;
      assertEquals(1, claimed);
    }
    var old = repo.find(id).orElseThrow();
    jdbc.update("UPDATE orders SET lease_until=now()-interval '1 second'");
    // A fresh repository represents a new process: coordination lives entirely in PostgreSQL.
    var recovered = new OrderRepository(jdbc).claim().orElseThrow();
    assertNotEquals(old.leaseToken(), recovered.leaseToken());
    repo.finish(old, true);
    assertEquals("pending", repo.find(id).orElseThrow().status());
    repo.finish(recovered, true);
    assertEquals("paid", repo.find(id).orElseThrow().status());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void paidAndEventCommitTogether() {
    UUID id = create();
    var claimed = repo.claim().orElseThrow();
    repo.finish(claimed, true);
    repo.finish(claimed, true);
    assertEquals("paid", repo.find(id).orElseThrow().status());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void failedOutboxInsertRollsBackPaid() {
    UUID id = create();
    var claimed = repo.claim().orElseThrow();
    jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT test_fail CHECK (false)");
    try {
      assertThrows(RuntimeException.class, () -> repo.finish(claimed, true));
      assertEquals("pending", repo.find(id).orElseThrow().status());
    } finally {
      jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT test_fail");
    }
  }

  @Test
  void concurrentDuplicateDeliveriesCreditExactlyOnce() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 100; i++) {
        int n = i % 10;
        tasks.add(
            () -> {
              events.deliver(new Delivery("event-" + n, "delivery.confirmed", "u", 7L));
              return null;
            });
      }
      for (var f : pool.invokeAll(tasks)) f.get();
    }
    assertEquals(70, events.points("u"));
    assertEquals(0, events.points("absent"));
  }

  @Test
  void failedCreditRollsBackInbox() {
    jdbc.update("INSERT INTO bonuses VALUES('u',?)", Long.MAX_VALUE);
    var event = new Delivery("retry", "delivery.confirmed", "u", 1L);
    assertThrows(RuntimeException.class, () -> events.deliver(event));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM delivery_inbox", Integer.class));
    jdbc.update("UPDATE bonuses SET points=0");
    events.deliver(event);
    assertEquals(1, events.points("u"));
  }

  @Test
  void outboxReplaysSameEventAfterAcceptanceWithoutLocalCommit() throws Exception {
    var accepted = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/events",
        exchange -> {
          accepted.add(json.readTree(exchange.getRequestBody()).get("id").asText());
          exchange.sendResponseHeaders(202, -1);
          exchange.close();
        });
    server.start();
    try {
      create();
      repo.finish(repo.claim().orElseThrow(), true);
      var publisher = new OutboxService(jdbc, "http://127.0.0.1:" + server.getAddress().getPort());
      jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT test_sent_failure CHECK (NOT sent)");
      try {
        publisher
            .publishNext(); // Bus accepts, marking sent fails; retry must preserve the event id.
      } finally {
        jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT test_sent_failure");
      }
      jdbc.update("UPDATE outbox SET next_attempt=now()");
      new OutboxService(jdbc, "http://127.0.0.1:" + server.getAddress().getPort()).publishNext();
      assertEquals(2, accepted.size());
      assertEquals(accepted.get(0), accepted.get(1));
      assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT sent FROM outbox", Boolean.class));
    } finally {
      server.stop(0);
    }
  }
}
