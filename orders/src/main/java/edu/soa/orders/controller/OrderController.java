package edu.soa.orders.controller;

import edu.soa.orders.dto.*;
import edu.soa.orders.service.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class OrderController {
  private final OrderService orders;
  private final EventService events;
  private final JdbcTemplate jdbc;

  public OrderController(OrderService orders, EventService events, JdbcTemplate jdbc) {
    this.orders = orders;
    this.events = events;
    this.jdbc = jdbc;
  }

  @GetMapping("/healthz")
  public void health() {
    jdbc.queryForObject("SELECT 1", Integer.class);
  }

  @PostMapping("/orders")
  public ResponseEntity<?> create(@Valid @RequestBody OrderRequest request) {
    return ResponseEntity.status(201)
        .body(Map.of("id", orders.create(request), "status", "pending"));
  }

  @GetMapping("/orders/{id}")
  public Map<String, Object> get(@PathVariable String id) {
    var o = orders.get(id);
    return Map.of("id", o.id(), "status", o.status(), "amount_cents", o.amountCents());
  }

  @PostMapping("/webhooks/delivery")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deliver(@Valid @RequestBody Delivery event) {
    events.deliver(event);
  }

  @GetMapping("/users/{id}/bonus")
  public Map<String, Long> bonus(@PathVariable String id) {
    return Map.of("points", events.points(id));
  }
}
