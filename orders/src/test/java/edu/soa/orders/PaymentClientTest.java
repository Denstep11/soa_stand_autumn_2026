package edu.soa.orders;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import edu.soa.orders.client.PaymentClient;
import edu.soa.orders.model.Order;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class PaymentClientTest {
  HttpServer server;
  ExecutorService executor;
  PaymentClient client;
  Order order;
  AtomicInteger posts = new AtomicInteger(), charges = new AtomicInteger();
  Set<String> keys = ConcurrentHashMap.newKeySet();
  volatile String mode = "ok";

  @BeforeEach
  void start() throws Exception {
    order = new Order(UUID.randomUUID(), "pending", 100, "[]", "PAY", 1, UUID.randomUUID());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/payments", this::handle);
    server.start();
    client = new PaymentClient("http://127.0.0.1:" + server.getAddress().getPort());
  }

  void handle(HttpExchange x) throws java.io.IOException {
    try {
      if (x.getRequestMethod().equals("GET")) {
        if (mode.equals("blackhole")) {
          sleep(8000);
          return;
        }
        reply(x, 200, charges.get() == 0 ? "[]" : "[" + charge() + "]");
        return;
      }
      posts.incrementAndGet();
      if (mode.equals("500")) {
        reply(x, 500, "{}");
        return;
      }
      if (mode.equals("decline")) {
        reply(x, 402, "{}");
        return;
      }
      if (mode.equals("blackhole")) {
        sleep(8000);
        return;
      }
      if (keys.add(x.getRequestHeaders().getFirst("Idempotency-Key"))) charges.incrementAndGet();
      if (mode.equals("lost")) return;
      if (mode.equals("late")) sleep(4200);
      reply(x, 201, charge());
    } finally {
      x.close();
    }
  }

  String charge() {
    return "{\"charge_id\":\"c1\",\"order_id\":\""
        + order.id()
        + "\",\"amount_cents\":100,\"currency\":\"RUB\",\"status\":\"captured\"}";
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static void reply(HttpExchange x, int status, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().add("Content-Type", "application/json");
    x.sendResponseHeaders(status, bytes.length);
    x.getResponseBody().write(bytes);
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  @Test
  void success() {
    assertEquals(PaymentClient.Result.CAPTURED, client.charge(order));
    assertEquals(1, charges.get());
  }

  @Test
  void declineIsDefinitive() {
    mode = "decline";
    assertEquals(PaymentClient.Result.DECLINED, client.charge(order));
    assertEquals(0, charges.get());
  }

  @Test
  void serverErrorIsUnknownThenRetrySucceeds() {
    mode = "500";
    assertEquals(PaymentClient.Result.UNKNOWN, client.charge(order));
    mode = "ok";
    assertEquals(PaymentClient.Result.CAPTURED, client.charge(order));
    assertEquals(1, charges.get());
  }

  @Test
  void lostResponseRecoveredByLookup() {
    mode = "lost";
    assertEquals(PaymentClient.Result.CAPTURED, client.charge(order));
    assertEquals(1, charges.get());
  }

  @Test
  void lateResponseRecoveredByLookup() {
    mode = "late";
    assertEquals(PaymentClient.Result.CAPTURED, client.charge(order));
    assertEquals(1, charges.get());
  }

  @Test
  void unavailableBankIsThrottled() {
    mode = "blackhole";
    assertEquals(PaymentClient.Result.UNKNOWN, client.charge(order));
    for (int i = 0; i < 100; i++) assertEquals(PaymentClient.Result.UNKNOWN, client.charge(order));
    assertEquals(1, posts.get());
    assertEquals(0, charges.get());
  }

  @Test
  void concurrentReplaysNeverChargeTwice() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<PaymentClient.Result>>();
      for (int i = 0; i < 20; i++) tasks.add(() -> client.charge(order));
      for (var result : pool.invokeAll(tasks))
        assertEquals(PaymentClient.Result.CAPTURED, result.get());
    }
    assertEquals(1, keys.size());
    assertEquals(1, charges.get());
  }

  @Test
  void restartAfterLostCommitUsesSamePaymentKey() {
    assertEquals(PaymentClient.Result.CAPTURED, client.charge(order));
    var restarted = new PaymentClient("http://127.0.0.1:" + server.getAddress().getPort());
    assertEquals(PaymentClient.Result.CAPTURED, restarted.charge(order));
    assertEquals(1, charges.get());
  }
}
