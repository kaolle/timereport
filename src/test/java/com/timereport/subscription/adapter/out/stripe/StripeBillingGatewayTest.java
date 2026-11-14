package com.timereport.subscription.adapter.out.stripe;

import com.stripe.StripeClient;
import com.stripe.model.Customer;
import com.stripe.model.Price;
import com.stripe.model.TaxRate;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.CustomerService;
import com.stripe.service.PriceService;
import com.stripe.service.TaxRateService;
import com.stripe.service.V1Services;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StripeBillingGatewayTest {
    private final StripeClient client = mock(StripeClient.class);
    private final V1Services v1 = mock(V1Services.class);
    private final PriceService prices = mock(PriceService.class);
    private final TaxRateService taxRates = mock(TaxRateService.class);
    private final CheckoutService checkout = mock(CheckoutService.class);
    private final com.stripe.service.checkout.SessionService sessions =
            mock(com.stripe.service.checkout.SessionService.class);

    @Test
    void createsCheckoutUsingTheSelectedStoredModelAndAccountMetadata() throws Exception {
        var gateway = gateway(true, "sk_test_example");
        prepareClient();
        var monthly = model(SubscriptionPlan.MONTHLY, false);
        var yearly = model(SubscriptionPlan.YEARLY, true);
        var monthlyPrice = price(monthly);
        var yearlyPrice = price(yearly);
        var monthlyTax = tax(monthly);
        var yearlyTax = tax(yearly);
        when(prices.retrieve(monthly.stripe().priceId())).thenReturn(monthlyPrice);
        when(prices.retrieve(yearly.stripe().priceId())).thenReturn(yearlyPrice);
        when(taxRates.retrieve(monthly.stripe().taxRateId())).thenReturn(monthlyTax);
        when(taxRates.retrieve(yearly.stripe().taxRateId())).thenReturn(yearlyTax);
        Session result = session();
        when(sessions.create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(result);
        UUID account = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();

        assertEquals("cs_test_123", gateway.createCheckout(account, attempt, "cus_test_123", monthly, "idem-1").sessionId());
        gateway.createCheckout(account, attempt, "cus_test_123", yearly, "idem-2");

        var params = ArgumentCaptor.forClass(SessionCreateParams.class);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(sessions, times(2)).create(params.capture(), options.capture());
        for (int index = 0; index < 2; index++) {
            var model = index == 0 ? monthly : yearly;
            var p = params.getAllValues().get(index);
            assertEquals(model.stripe().priceId(), p.getLineItems().getFirst().getPrice());
            assertEquals(model.stripe().taxRateId(), p.getLineItems().getFirst().getTaxRates().getFirst());
            assertEquals(SessionCreateParams.Mode.SUBSCRIPTION, p.getMode());
            assertEquals("cus_test_123", p.getCustomer());
            assertEquals(attempt.toString(), p.getClientReferenceId());
            assertEquals(account.toString(), p.getMetadata().get("timereport_account_id"));
            assertEquals(model.id().toString(), p.getMetadata().get("timereport_price_model_id"));
            assertEquals(model.id().toString(), p.getSubscriptionData().getMetadata().get("timereport_price_model_id"));
            assertEquals(1L, p.getLineItems().getFirst().getQuantity());
            assertEquals("http://localhost:8080/?subscription=processing", p.getSuccessUrl());
        }
        assertEquals("idem-1", options.getAllValues().get(0).getIdempotencyKey());
        assertEquals("idem-2", options.getAllValues().get(1).getIdempotencyKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"price-live", "price-inactive", "price-id", "product", "amount", "currency", "interval",
            "interval-count", "tax-behavior", "tax-live", "tax-inactive", "tax-id", "tax-percent", "tax-inclusive"})
    void refusesStripeValuesThatNoLongerMatchTheStoredPriceModel(String mismatch) throws Exception {
        var gateway = gateway(true, "sk_test_example");
        prepareClient();
        var model = model(SubscriptionPlan.MONTHLY, false);
        Price price = price(model);
        TaxRate tax = tax(model);
        switch (mismatch) {
            case "price-live" -> when(price.getLivemode()).thenReturn(true);
            case "price-inactive" -> when(price.getActive()).thenReturn(false);
            case "price-id" -> when(price.getId()).thenReturn("price_other");
            case "product" -> when(price.getProduct()).thenReturn("prod_other");
            case "amount" -> when(price.getUnitAmount()).thenReturn(model.amountMinor() + 1);
            case "currency" -> when(price.getCurrency()).thenReturn("eur");
            case "interval" -> when(price.getRecurring().getInterval()).thenReturn("year");
            case "interval-count" -> when(price.getRecurring().getIntervalCount()).thenReturn(2L);
            case "tax-behavior" -> when(price.getTaxBehavior()).thenReturn("inclusive");
            case "tax-live" -> when(tax.getLivemode()).thenReturn(true);
            case "tax-inactive" -> when(tax.getActive()).thenReturn(false);
            case "tax-id" -> when(tax.getId()).thenReturn("txr_other");
            case "tax-percent" -> when(tax.getPercentage()).thenReturn(new BigDecimal("12"));
            case "tax-inclusive" -> when(tax.getInclusive()).thenReturn(true);
        }
        when(prices.retrieve(model.stripe().priceId())).thenReturn(price);
        when(taxRates.retrieve(model.stripe().taxRateId())).thenReturn(tax);

        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway.createCheckout(UUID.randomUUID(),
                UUID.randomUUID(), "cus_test_123", model, "idem"));
        verify(sessions, never()).create(any(SessionCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void refusesAnUnfinishedPriceModelBeforeCallingStripe() {
        var model = model(SubscriptionPlan.MONTHLY, false).toBuilder()
                .state(SubscriptionPriceModel.State.CREATING).stripe(null).active(false).build();

        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway(true, "sk_test_example")
                .createCheckout(UUID.randomUUID(), UUID.randomUUID(), "cus_test_123", model, "idem"));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsLiveKeysBeforeAnyStripeCall() {
        var gateway = gateway(true, "sk_live_must_not_be_used_in_stage_two");

        assertThrows(SubscriptionBillingUnavailable.class, gateway::requireCheckoutConfigured);
        assertThrows(SubscriptionBillingUnavailable.class, gateway::requirePricingConfigured);
        verifyNoInteractions(client);
    }

    @Test
    void createsCustomerUsingStoredRandomIdempotencyKey() throws Exception {
        var gateway = gateway(true, "sk_test_example");
        CustomerService customers = mock(CustomerService.class);
        Customer customer = mock(Customer.class);
        when(client.v1()).thenReturn(v1);
        when(v1.customers()).thenReturn(customers);
        when(customer.getId()).thenReturn("cus_test_123");
        when(customer.getLivemode()).thenReturn(false);
        when(customers.create(any(CustomerCreateParams.class), any(RequestOptions.class))).thenReturn(customer);
        UUID account = UUID.randomUUID();

        assertEquals("cus_test_123", gateway.createCustomer(account, "buyer@example.test", "random-key-42"));
        var params = ArgumentCaptor.forClass(CustomerCreateParams.class);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(customers).create(params.capture(), options.capture());
        assertEquals("buyer@example.test", params.getValue().getEmail());
        assertEquals(account.toString(), ((Map<?, ?>) params.getValue().getMetadata()).get("timereport_account_id"));
        assertEquals("random-key-42", options.getValue().getIdempotencyKey());
    }

    @Test
    void checkoutIsOffWhilePriceProvisioningAndPortalRemainAvailable() {
        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway(false, "sk_test_example").requireCheckoutConfigured());
        assertDoesNotThrow(() -> gateway(false, "sk_test_example").requirePricingConfigured());
        assertDoesNotThrow(() -> gateway(false, "sk_test_example").requirePortalConfigured());
    }

    private void prepareClient() {
        when(client.v1()).thenReturn(v1);
        when(v1.prices()).thenReturn(prices);
        when(v1.taxRates()).thenReturn(taxRates);
        when(v1.checkout()).thenReturn(checkout);
        when(checkout.sessions()).thenReturn(sessions);
    }

    private StripeBillingGateway gateway(boolean enabled, String key) {
        return new StripeBillingGateway(enabled, Optional.of(key), Optional.of("bpc_test"), "http://localhost:8080") {
            @Override
            StripeClient client() { return client; }
        };
    }

    private SubscriptionPriceModel model(SubscriptionPlan plan, boolean inclusive) {
        String suffix = plan == SubscriptionPlan.MONTHLY ? "month" : "year";
        return SubscriptionPriceModel.builder().id(UUID.randomUUID()).name("Test " + suffix).plan(plan)
                .amount(new BigDecimal(plan == SubscriptionPlan.MONTHLY ? "99.00" : "990.00"))
                .vatPercent(new BigDecimal("25.00")).vatInclusive(inclusive).state(SubscriptionPriceModel.State.READY)
                .stripe(new StripePriceReferences("prod_" + suffix, "price_" + suffix, "txr_" + suffix))
                .active(true).createdBy(UUID.randomUUID()).createdAt(Instant.parse("2026-11-14T12:00:00Z")).build();
    }

    private Price price(SubscriptionPriceModel model) {
        Price price = mock(Price.class);
        Price.Recurring recurring = mock(Price.Recurring.class);
        when(price.getId()).thenReturn(model.stripe().priceId());
        when(price.getProduct()).thenReturn(model.stripe().productId());
        when(price.getActive()).thenReturn(true);
        when(price.getLivemode()).thenReturn(false);
        when(price.getCurrency()).thenReturn("sek");
        when(price.getTaxBehavior()).thenReturn(model.vatInclusive() ? "inclusive" : "exclusive");
        when(price.getRecurring()).thenReturn(recurring);
        when(price.getUnitAmount()).thenReturn(model.amountMinor());
        when(recurring.getInterval()).thenReturn(model.plan() == SubscriptionPlan.MONTHLY ? "month" : "year");
        when(recurring.getIntervalCount()).thenReturn(1L);
        return price;
    }

    private TaxRate tax(SubscriptionPriceModel model) {
        TaxRate tax = mock(TaxRate.class);
        when(tax.getId()).thenReturn(model.stripe().taxRateId());
        when(tax.getActive()).thenReturn(true);
        when(tax.getLivemode()).thenReturn(false);
        when(tax.getPercentage()).thenReturn(model.vatPercent());
        when(tax.getInclusive()).thenReturn(model.vatInclusive());
        return tax;
    }

    private Session session() {
        Session session = mock(Session.class);
        when(session.getLivemode()).thenReturn(false);
        when(session.getId()).thenReturn("cs_test_123");
        when(session.getCustomer()).thenReturn("cus_test_123");
        when(session.getExpiresAt()).thenReturn(2_000_000_000L);
        when(session.getStatus()).thenReturn("open");
        when(session.getUrl()).thenReturn("https://checkout.stripe.test/session");
        return session;
    }
}
