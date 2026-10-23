package edu.soa.orders.service;

import edu.soa.orders.client.*;
import edu.soa.orders.model.Order;
import edu.soa.orders.repository.OrderRepository;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class OrderProcessingService {
  private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);
  private final OrderRepository orders;
  private final PaymentClient payment;
  private final InventoryClient inventory;

  public OrderProcessingService(
      OrderRepository orders, PaymentClient payment, InventoryClient inventory) {
    this.orders = orders;
    this.payment = payment;
    this.inventory = inventory;
  }

  public boolean processNext() {
    var next = orders.claim();
    if (next.isEmpty()) return false;
    Order o = next.get();
    try {
      switch (o.phase()) {
        case "RESERVE" -> {
          inventory.reserve(o);
          orders.advance(o, "PAY");
        }
        case "PAY" -> {
          var result = o.amountCents() == 0 ? PaymentClient.Result.UNKNOWN : payment.charge(o);
          switch (result) {
            case CAPTURED -> orders.finish(o, true);
            case DECLINED -> orders.advance(o, "CANCEL");
            case UNKNOWN -> orders.retry(o);
          }
        }
        case "CANCEL" -> {
          inventory.cancel(o);
          orders.finish(o, false);
        }
        default -> throw new IllegalStateException("Unexpected phase " + o.phase());
      }
    } catch (RuntimeException e) {
      log.warn("Order {} phase {} deferred: {}", o.id(), o.phase(), e.toString());
      orders.retry(o);
    }
    return true;
  }
}
