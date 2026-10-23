package edu.soa.orders.service;

import edu.soa.orders.dto.Delivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  private final JdbcTemplate jdbc;

  public EventService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void deliver(Delivery event) {
    int inserted =
        jdbc.update("INSERT INTO delivery_inbox(id) VALUES(?) ON CONFLICT DO NOTHING", event.id());
    if (inserted == 1)
      jdbc.update(
          """
          INSERT INTO bonuses(user_id,points) VALUES(?,?)
          ON CONFLICT(user_id) DO UPDATE SET points=bonuses.points+excluded.points
          """,
          event.userId(),
          event.bonusPoints());
  }

  public long points(String user) {
    var rows = jdbc.queryForList("SELECT points FROM bonuses WHERE user_id=?", Long.class, user);
    return rows.isEmpty() ? 0 : rows.getFirst();
  }
}
