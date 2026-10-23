package edu.soa.orders.scheduler;

import edu.soa.orders.service.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "orders.workers-enabled", havingValue = "true", matchIfMissing = true)
public class OrderRecoveryScheduler {
  private static final Logger log = LoggerFactory.getLogger(OrderRecoveryScheduler.class);
  private final OrderProcessingService orders;
  private final OutboxService outbox;
  private final ExecutorService pool = Executors.newFixedThreadPool(8);
  private final Semaphore orderSlots = new Semaphore(4), eventSlots = new Semaphore(4);

  public OrderRecoveryScheduler(OrderProcessingService orders, OutboxService outbox) {
    this.orders = orders;
    this.outbox = outbox;
  }

  @Scheduled(fixedDelay = 50)
  public void tick() {
    submit(orderSlots, orders::processNext);
    submit(eventSlots, outbox::publishNext);
  }

  private void submit(Semaphore slots, java.util.function.BooleanSupplier work) {
    if (!slots.tryAcquire()) return;
    pool.submit(
        () -> {
          try {
            for (int i = 0; i < 20 && work.getAsBoolean(); i++) {
              /* bounded batch */
            }
          } catch (RuntimeException e) {
            log.warn("Background batch deferred: {}", e.toString());
          } finally {
            slots.release();
          }
        });
  }

  @PreDestroy
  public void stop() {
    pool.shutdownNow();
  }
}
