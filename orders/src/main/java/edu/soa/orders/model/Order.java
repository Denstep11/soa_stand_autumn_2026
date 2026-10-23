package edu.soa.orders.model;

import java.util.UUID;

public record Order(
    UUID id,
    String status,
    long amountCents,
    String items,
    String phase,
    int attempts,
    UUID leaseToken) {}
