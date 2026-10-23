package edu.soa.orders.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.soa.orders.model.Order;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class PaymentClient {
  public enum Result {
    CAPTURED,
    DECLINED,
    UNKNOWN
  }

  private final RestClient http;
  private long openUntil;
  private boolean probing;

  public PaymentClient(@Value("${orders.payment-url}") String url) {
    http = HttpClients.create(url, Duration.ofMillis(3500));
  }

  // Closed: the bounded worker pool can call concurrently. Half-open: exactly one probe.
  private synchronized int acquire() {
    if (openUntil == 0) return 1;
    if (System.nanoTime() < openUntil || probing) return 0;
    probing = true;
    return 2;
  }

  private synchronized void reachable() {
    openUntil = 0;
  }

  private synchronized void unavailable() {
    openUntil = System.nanoTime() + 5_000_000_000L;
  }

  private synchronized void release() {
    probing = false;
  }

  public Result charge(Order order) {
    int permit = acquire();
    if (permit == 0) return Result.UNKNOWN;
    try {
      if (order.attempts() > 1 && captured(order)) {
        reachable();
        return Result.CAPTURED;
      }
      try {
        JsonNode body =
            http.post()
                .uri("/payments")
                .header("Idempotency-Key", "order:" + order.id())
                .body(
                    Map.of(
                        "order_id",
                        order.id().toString(),
                        "amount_cents",
                        order.amountCents(),
                        "currency",
                        "RUB"))
                .retrieve()
                .body(JsonNode.class);
        reachable();
        return matches(body, order) ? Result.CAPTURED : Result.UNKNOWN;
      } catch (HttpClientErrorException e) {
        reachable();
        return e.getStatusCode().value() == 402 ? Result.DECLINED : Result.UNKNOWN;
      } catch (ResourceAccessException e) {
        // Lookup may prove capture, but an empty result never proves a decline.
        if (captured(order)) {
          reachable();
          return Result.CAPTURED;
        }
        unavailable();
        return Result.UNKNOWN;
      }
    } catch (ResourceAccessException e) {
      unavailable();
      return Result.UNKNOWN;
    } catch (RestClientException e) {
      // Includes 500: preserve pending and retry the same immutable operation.
      return Result.UNKNOWN;
    } finally {
      if (permit == 2) release();
    }
  }

  private boolean captured(Order order) {
    JsonNode rows =
        http.get().uri("/payments?order_id={id}", order.id()).retrieve().body(JsonNode.class);
    if (rows != null && rows.isArray()) {
      for (JsonNode row : rows) if (matches(row, order)) return true;
    }
    return false;
  }

  private boolean matches(JsonNode row, Order order) {
    return row != null
        && "captured".equals(row.path("status").asText())
        && order.id().toString().equals(row.path("order_id").asText())
        && order.amountCents() == row.path("amount_cents").asLong(-1)
        && "RUB".equals(row.path("currency").asText());
  }
}
