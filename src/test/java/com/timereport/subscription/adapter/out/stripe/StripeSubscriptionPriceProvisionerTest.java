package com.timereport.subscription.adapter.out.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.TaxRate;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.TaxRateCreateParams;
import com.stripe.service.PriceService;
import com.stripe.service.ProductService;
import com.stripe.service.TaxRateService;
import com.stripe.service.V1Services;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StripeSubscriptionPriceProvisionerTest {
    private final StripeClient client = mock(StripeClient.class);
    private final V1Services v1 = mock(V1Services.class);
    private final ProductService products = mock(ProductService.class);
    private final TaxRateService taxRates = mock(TaxRateService.class);
    private final PriceService prices = mock(PriceService.class);

    @ParameterizedTest
    @EnumSource(SubscriptionPlan.class)
    void provisionsTheRequestedRecurringPriceAndTaxWhileCheckoutIsDisabled(SubscriptionPlan plan) throws Exception {
        var model = model(plan);
        prepare(model);

        assertEquals(new StripePriceReferences("prod_model", "price_model", "txr_model"),
                provisioner("sk_test_example").provision(model));

        var productParams = ArgumentCaptor.forClass(ProductCreateParams.class);
        var taxParams = ArgumentCaptor.forClass(TaxRateCreateParams.class);
        var priceParams = ArgumentCaptor.forClass(PriceCreateParams.class);
        verify(products).create(productParams.capture(), any(RequestOptions.class));
        verify(taxRates).create(taxParams.capture(), any(RequestOptions.class));
        verify(prices).create(priceParams.capture(), any(RequestOptions.class));
        assertEquals(model.name(), productParams.getValue().getName());
        assertEquals(model.id().toString(), productParams.getValue().getMetadata().get("timereport_price_model_id"));
        assertEquals(model.vatPercent(), taxParams.getValue().getPercentage());
        assertEquals(model.vatInclusive(), taxParams.getValue().getInclusive());
        assertEquals("SE", taxParams.getValue().getCountry());
        assertEquals(model.id().toString(), taxParams.getValue().getMetadata().get("timereport_price_model_id"));
        var price = priceParams.getValue();
        assertEquals("prod_model", price.getProduct());
        assertEquals("sek", price.getCurrency());
        assertEquals(model.amountMinor(), price.getUnitAmount());
        assertEquals(plan == SubscriptionPlan.MONTHLY ? PriceCreateParams.Recurring.Interval.MONTH
                : PriceCreateParams.Recurring.Interval.YEAR, price.getRecurring().getInterval());
        assertEquals(1L, price.getRecurring().getIntervalCount());
        assertEquals(model.vatInclusive() ? PriceCreateParams.TaxBehavior.INCLUSIVE
                : PriceCreateParams.TaxBehavior.EXCLUSIVE, price.getTaxBehavior());
        assertEquals(model.id().toString(), price.getMetadata().get("timereport_price_model_id"));
    }

    @Test
    void retriesReuseEachOperationsIdempotencyKeyAfterPartialFailure() throws Exception {
        var model = model(SubscriptionPlan.MONTHLY);
        var created = prepare(model);
        when(taxRates.create(any(TaxRateCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("temporary test failure")).thenReturn(created.tax());
        var provisioner = provisioner("sk_test_example");

        assertThrows(SubscriptionBillingUnavailable.class, () -> provisioner.provision(model));
        assertEquals("price_model", provisioner.provision(model).priceId());

        var productOptions = ArgumentCaptor.forClass(RequestOptions.class);
        var taxOptions = ArgumentCaptor.forClass(RequestOptions.class);
        var priceOptions = ArgumentCaptor.forClass(RequestOptions.class);
        verify(products, times(2)).create(any(ProductCreateParams.class), productOptions.capture());
        verify(taxRates, times(2)).create(any(TaxRateCreateParams.class), taxOptions.capture());
        verify(prices).create(any(PriceCreateParams.class), priceOptions.capture());
        assertEquals(productOptions.getAllValues().get(0).getIdempotencyKey(),
                productOptions.getAllValues().get(1).getIdempotencyKey());
        assertEquals(taxOptions.getAllValues().get(0).getIdempotencyKey(),
                taxOptions.getAllValues().get(1).getIdempotencyKey());
        var keys = Set.of(productOptions.getValue().getIdempotencyKey(), taxOptions.getValue().getIdempotencyKey(),
                priceOptions.getValue().getIdempotencyKey());
        assertEquals(3, keys.size());
        for (String key : keys) assertTrue(key.contains(model.id().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "tax", "price"})
    void refusesLiveEntitiesReturnedByStripe(String entity) throws Exception {
        var model = model(SubscriptionPlan.MONTHLY);
        var created = prepare(model);
        switch (entity) {
            case "product" -> when(created.product().getLivemode()).thenReturn(true);
            case "tax" -> when(created.tax().getLivemode()).thenReturn(true);
            case "price" -> when(created.price().getLivemode()).thenReturn(true);
        }

        assertThrows(SubscriptionBillingUnavailable.class, () -> provisioner("sk_test_example").provision(model));
        if (entity.equals("product")) verifyNoInteractions(taxRates, prices);
        if (entity.equals("tax")) verifyNoInteractions(prices);
    }

    @Test
    void refusesAPriceCreatedForAnotherProduct() throws Exception {
        var model = model(SubscriptionPlan.MONTHLY);
        var created = prepare(model);
        when(created.price().getProduct()).thenReturn("prod_other");

        assertThrows(SubscriptionBillingUnavailable.class, () -> provisioner("sk_test_example").provision(model));
    }

    @Test
    void refusesLiveOrMissingKeysBeforeCallingStripe() {
        var model = model(SubscriptionPlan.MONTHLY);

        assertThrows(SubscriptionBillingUnavailable.class, () -> provisioner("sk_live_forbidden").provision(model));
        assertThrows(SubscriptionBillingUnavailable.class, () -> provisioner("").provision(model));
        verifyNoInteractions(client);
    }

    private StripeSubscriptionPriceProvisioner provisioner(String key) {
        var gateway = new StripeBillingGateway(false, Optional.of(key), Optional.empty(), "https://setup.invalid") {
            @Override
            StripeClient client() { return client; }
        };
        return new StripeSubscriptionPriceProvisioner(gateway);
    }

    private SubscriptionPriceModel model(SubscriptionPlan plan) {
        return SubscriptionPriceModel.builder().id(UUID.randomUUID()).name("Fakturering " + plan).plan(plan)
                .amount(new BigDecimal(plan == SubscriptionPlan.MONTHLY ? "49.00" : "490.00"))
                .vatPercent(new BigDecimal("25.00")).vatInclusive(plan == SubscriptionPlan.YEARLY)
                .state(SubscriptionPriceModel.State.CREATING).createdBy(UUID.randomUUID())
                .createdAt(Instant.parse("2026-11-14T12:00:00Z")).build();
    }

    private Created prepare(SubscriptionPriceModel model) throws Exception {
        when(client.v1()).thenReturn(v1);
        when(v1.products()).thenReturn(products);
        when(v1.taxRates()).thenReturn(taxRates);
        when(v1.prices()).thenReturn(prices);
        Product product = mock(Product.class);
        when(product.getId()).thenReturn("prod_model");
        when(product.getActive()).thenReturn(true);
        when(product.getLivemode()).thenReturn(false);
        when(products.create(any(ProductCreateParams.class), any(RequestOptions.class))).thenReturn(product);
        TaxRate tax = mock(TaxRate.class);
        when(tax.getId()).thenReturn("txr_model");
        when(tax.getActive()).thenReturn(true);
        when(tax.getLivemode()).thenReturn(false);
        when(tax.getPercentage()).thenReturn(model.vatPercent());
        when(tax.getInclusive()).thenReturn(model.vatInclusive());
        when(taxRates.create(any(TaxRateCreateParams.class), any(RequestOptions.class))).thenReturn(tax);
        Price price = mock(Price.class);
        Price.Recurring recurring = mock(Price.Recurring.class);
        when(price.getId()).thenReturn("price_model");
        when(price.getProduct()).thenReturn("prod_model");
        when(price.getActive()).thenReturn(true);
        when(price.getLivemode()).thenReturn(false);
        when(price.getCurrency()).thenReturn("sek");
        when(price.getUnitAmount()).thenReturn(model.amountMinor());
        when(price.getTaxBehavior()).thenReturn(model.vatInclusive() ? "inclusive" : "exclusive");
        when(price.getRecurring()).thenReturn(recurring);
        when(recurring.getInterval()).thenReturn(model.plan() == SubscriptionPlan.MONTHLY ? "month" : "year");
        when(recurring.getIntervalCount()).thenReturn(1L);
        when(prices.create(any(PriceCreateParams.class), any(RequestOptions.class))).thenReturn(price);
        return new Created(product, tax, price);
    }

    private record Created(Product product, TaxRate tax, Price price) { }
}
