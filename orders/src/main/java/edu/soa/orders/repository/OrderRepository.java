package edu.soa.orders.repository;

import edu.soa.orders.model.Order;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {
  private final JdbcTemplate jdbc;

  public OrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<Order> mapper =
      (r, n) ->
          new Order(
              r.getObject("id", UUID.class),
              r.getString("status"),
              r.getLong("amount_cents"),
              r.getString("items"),
              r.getString("phase"),
              r.getInt("attempts"),
              r.getObject("lease_token", UUID.class));

  public void insert(UUID id, String user, long amount, String items) {
    jdbc.update(
        "INSERT INTO orders(id,user_id,amount_cents,items) VALUES(?,?,?,?::jsonb)",
        id,
        user,
        amount,
        items);
  }

  public Optional<Order> find(UUID id) {
    return jdbc.query("SELECT * FROM orders WHERE id=?", mapper, id).stream().findFirst();
  }

  public Optional<Order> claim() {
    return jdbc
        .query(
            """
UPDATE orders SET lease_token=?, lease_until=now()+interval '15 seconds', attempts=attempts+1
WHERE id=(SELECT id FROM orders WHERE status='pending' AND next_attempt<=now()
AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_attempt,created_at
FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *
""",
            mapper,
            UUID.randomUUID())
        .stream()
        .findFirst();
  }

  public void advance(Order o, String phase) {
    jdbc.update(
        "UPDATE orders SET phase=?,attempts=0,next_attempt=now(),lease_until=NULL,lease_token=NULL"
            + " WHERE id=? AND lease_token=?",
        phase,
        o.id(),
        o.leaseToken());
  }

  public void retry(Order o) {
    long delay =
        Math.min(5000, 200L << Math.min(o.attempts(), 5))
            + java.util.concurrent.ThreadLocalRandom.current().nextLong(100);
    jdbc.update(
        "UPDATE orders SET next_attempt=now()+(? * interval '1"
            + " millisecond'),lease_until=NULL,lease_token=NULL WHERE id=? AND lease_token=?",
        delay,
        o.id(),
        o.leaseToken());
  }

  @Transactional
  public void finish(Order o, boolean paid) {
    int updated =
        jdbc.update(
            "UPDATE orders SET status=?,phase='DONE',lease_until=NULL,lease_token=NULL WHERE id=?"
                + " AND lease_token=?",
            paid ? "paid" : "rejected",
            o.id(),
            o.leaseToken());
    if (updated == 1 && paid)
      jdbc.update(
          "INSERT INTO outbox(id,order_id) VALUES(?,?) ON CONFLICT(order_id) DO NOTHING",
          UUID.randomUUID(),
          o.id());
  }
}
