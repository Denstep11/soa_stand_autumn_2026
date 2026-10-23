package edu.soa.orders.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record OrderRequest(@NotEmpty String userId, @NotEmpty List<@NotNull @Valid Item> items) {
  public record Item(
      @NotEmpty String sku, @NotNull @Min(1) Integer qty, @NotNull @Min(0) Long priceCents) {}
}
