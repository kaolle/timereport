package com.timereport.subscription.domain;

import lombok.Builder;

@Builder
public record StripePriceReferences(String productId, String priceId, String taxRateId) {
    public StripePriceReferences {
        if (productId == null || !productId.startsWith("prod_")
                || priceId == null || !priceId.startsWith("price_")
                || taxRateId == null || !taxRateId.startsWith("txr_"))
            throw new IllegalArgumentException("Ogiltiga Stripe-referenser för priset.");
    }
}
