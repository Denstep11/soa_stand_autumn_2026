package edu.soa.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.soa.orders.dto.OrderRequest;
import edu.soa.orders.repository.OrderRepository;
import edu.soa.orders.service.OrderService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OrderServiceTest {
  private final OrderRepository repository = mock(OrderRepository.class);
  private final OrderService service = new OrderService(repository, new ObjectMapper());

  @Test
  void savesExactTotalBeforeReturningId() {
    var id =
        service.create(
            new OrderRequest("u-17", List.of(new OrderRequest.Item("sku-1", 2, 49900L))));
    verify(repository).insert(eq(id), eq("u-17"), eq(99800L), contains("sku-1"));
  }

  @Test
  void rejectsMultiplicationOverflowBeforePersisting() {
    var request = new OrderRequest("u", List.of(new OrderRequest.Item("sku", 2, Long.MAX_VALUE)));
    var error = assertThrows(ResponseStatusException.class, () -> service.create(request));
    assertEquals(400, error.getStatusCode().value());
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsSumOverflowBeforePersisting() {
    var request =
        new OrderRequest(
            "u",
            List.of(
                new OrderRequest.Item("sku", 1, Long.MAX_VALUE),
                new OrderRequest.Item("sku", 1, 1L)));
    assertThrows(ResponseStatusException.class, () -> service.create(request));
    verifyNoInteractions(repository);
  }

  @Test
  void acceptsZeroAmountAsRequiredByOrdersSchema() {
    var id = service.create(new OrderRequest("u", List.of(new OrderRequest.Item("free", 1, 0L))));
    verify(repository).insert(eq(id), eq("u"), eq(0L), anyString());
  }
}
