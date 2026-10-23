package edu.soa.orders.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.soa.orders.dto.OrderRequest;
import edu.soa.orders.model.Order;
import edu.soa.orders.repository.OrderRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
  private final OrderRepository orders;
  private final ObjectMapper json;

  public OrderService(OrderRepository orders, ObjectMapper json) {
    this.orders = orders;
    this.json = json;
  }

  public UUID create(OrderRequest request) {
    long amount = 0;
    try {
      for (var item : request.items())
        amount = Math.addExact(amount, Math.multiplyExact((long) item.qty(), item.priceCents()));
    } catch (ArithmeticException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount exceeds int64");
    }
    UUID id = UUID.randomUUID();
    try {
      var items =
          request.items().stream().map(i -> Map.of("sku", i.sku(), "qty", i.qty())).toList();
      orders.insert(id, request.userId(), amount, json.writeValueAsString(items));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return id;
  }

  public Order get(String id) {
    try {
      return orders
          .find(UUID.fromString(id))
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }
}
