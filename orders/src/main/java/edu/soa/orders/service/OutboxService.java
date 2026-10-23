package edu.soa.orders.service;

import edu.soa.orders.client.HttpClients;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OutboxService {
  private final JdbcTemplate jdbc;
  private final RestClient http;

  public OutboxService(JdbcTemplate jdbc, @Value("${orders.events-url}") String url) {
    this.jdbc = jdbc;
    http = HttpClients.create(url);
  }

  public boolean publishNext() {
    UUID token = UUID.randomUUID();
    var rows =
        jdbc.queryForList(
            """
UPDATE outbox SET lease_token=?,lease_until=now()+interval '15 seconds',attempts=attempts+1
WHERE id=(SELECT id FROM outbox WHERE NOT sent AND next_attempt<=now()
 AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_attempt
 FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *
""",
            token);
    if (rows.isEmpty()) return false;
    var row = rows.getFirst();
    try {
      var response =
          http.post()
              .uri("/events")
              .body(
                  Map.of(
                      "id",
                      row.get("id").toString(),
                      "type",
                      "order.paid",
                      "order_id",
                      row.get("order_id").toString(),
                      "occurred_at",
                      ((java.sql.Timestamp) row.get("occurred_at")).toInstant().toString()))
              .retrieve()
              .toBodilessEntity();
      if (response.getStatusCode().value() != 202)
        throw new IllegalStateException("Event not accepted");
      jdbc.update(
          "UPDATE outbox SET sent=true,lease_until=NULL,lease_token=NULL WHERE id=? AND"
              + " lease_token=?",
          row.get("id"),
          token);
    } catch (RuntimeException e) {
      long delay = Math.min(5000, 200L << Math.min(((Number) row.get("attempts")).intValue(), 5));
      jdbc.update(
          "UPDATE outbox SET next_attempt=now()+(? * interval '1"
              + " millisecond'),lease_until=NULL,lease_token=NULL WHERE id=? AND lease_token=?",
          delay,
          row.get("id"),
          token);
    }
    return true;
  }
}
