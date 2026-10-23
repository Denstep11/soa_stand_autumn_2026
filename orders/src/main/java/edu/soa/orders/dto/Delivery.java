package edu.soa.orders.dto;

import jakarta.validation.constraints.*;

public record Delivery(
    @NotEmpty String id,
    @NotNull @Pattern(regexp = "delivery\\.confirmed") String type,
    @NotEmpty String userId,
    @NotNull @Min(1) Long bonusPoints) {}
