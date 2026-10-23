package edu.soa.orders.client;

import com.fasterxml.jackson.databind.*;
import edu.soa.orders.model.Order;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryClient {
  private final RestClient http;
  private final ObjectMapper json;

  public InventoryClient(@Value("${orders.inventory-url}") String url, ObjectMapper json) {
    http = HttpClients.create(url);
    this.json = json;
  }

  public void reserve(Order o) {
    try {
      JsonNode items = json.readTree(o.items());
      http.post()
          .uri("/reservations")
          .body(Map.of("order_id", o.id().toString(), "items", items))
          .retrieve()
          .toBodilessEntity();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public void cancel(Order o) {
    http.delete().uri("/reservations/{id}", o.id()).retrieve().toBodilessEntity();
  }
}
