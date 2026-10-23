package edu.soa.orders;

import static org.mockito.Mockito.*;

import edu.soa.orders.client.*;
import edu.soa.orders.model.Order;
import edu.soa.orders.repository.OrderRepository;
import edu.soa.orders.service.OrderProcessingService;
import java.util.*;
import org.junit.jupiter.api.*;

class OrderProcessingServiceTest {
  OrderRepository repo = mock(OrderRepository.class);
  PaymentClient payment = mock(PaymentClient.class);
  InventoryClient inventory = mock(InventoryClient.class);
  OrderProcessingService processor = new OrderProcessingService(repo, payment, inventory);

  Order order(String phase) {
    var o = new Order(UUID.randomUUID(), "pending", 100, "[]", phase, 1, UUID.randomUUID());
    when(repo.claim()).thenReturn(Optional.of(o));
    return o;
  }

  @Test
  void reserveBeforePayment() {
    var o = order("RESERVE");
    processor.processNext();
    var sequence = inOrder(inventory, repo);
    sequence.verify(inventory).reserve(o);
    sequence.verify(repo).advance(o, "PAY");
    verifyNoInteractions(payment);
  }

  @Test
  void inventoryFailureRetainsPendingWithoutCharging() {
    var o = order("RESERVE");
    doThrow(new RuntimeException("unavailable")).when(inventory).reserve(o);
    processor.processNext();
    verify(repo).retry(o);
    verifyNoInteractions(payment);
    verify(repo, never()).finish(any(), anyBoolean());
  }

  @Test
  void capturedBecomesPaid() {
    var o = order("PAY");
    when(payment.charge(o)).thenReturn(PaymentClient.Result.CAPTURED);
    processor.processNext();
    verify(repo).finish(o, true);
  }

  @Test
  void unknownNeverCancelsReservation() {
    var o = order("PAY");
    when(payment.charge(o)).thenReturn(PaymentClient.Result.UNKNOWN);
    processor.processNext();
    verify(repo).retry(o);
    verifyNoInteractions(inventory);
    verify(repo, never()).finish(any(), anyBoolean());
  }

  @Test
  void declinePersistsCompensationBeforeExternalCall() {
    var o = order("PAY");
    when(payment.charge(o)).thenReturn(PaymentClient.Result.DECLINED);
    processor.processNext();
    verify(repo).advance(o, "CANCEL");
    verifyNoInteractions(inventory);
  }

  @Test
  void rejectionOnlyAfterSuccessfulRelease() {
    var o = order("CANCEL");
    processor.processNext();
    var sequence = inOrder(inventory, repo);
    sequence.verify(inventory).cancel(o);
    sequence.verify(repo).finish(o, false);
  }

  @Test
  void failedCompensationRemainsPending() {
    var o = order("CANCEL");
    doThrow(new RuntimeException("lost reply")).when(inventory).cancel(o);
    processor.processNext();
    verify(repo).retry(o);
    verify(repo, never()).finish(any(), anyBoolean());
  }
}
