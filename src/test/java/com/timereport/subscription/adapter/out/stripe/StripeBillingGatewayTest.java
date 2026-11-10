package com.timereport.subscription.adapter.out.stripe;

import com.stripe.StripeClient;
import com.stripe.model.Price;
import com.stripe.model.TaxRate;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.service.CheckoutService;
import com.stripe.service.CustomerService;
import com.stripe.service.PriceService;
import com.stripe.service.TaxRateService;
import com.stripe.service.V1Services;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Map;
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
    void createsTestCheckoutWithOnlyTheServerSelectedPlanPriceAndAccountMetadata() throws Exception {
        var gateway = gateway(true, "sk_test_example");
        when(client.v1()).thenReturn(v1);
        when(v1.prices()).thenReturn(prices);
        when(v1.taxRates()).thenReturn(taxRates);
        when(v1.checkout()).thenReturn(checkout);
        when(checkout.sessions()).thenReturn(sessions);
        Price monthly = price("price_month", "month");
        Price yearly = price("price_year", "year");
        when(prices.retrieve("price_month")).thenReturn(monthly);
        when(prices.retrieve("price_year")).thenReturn(yearly);
        TaxRate rate = mock(TaxRate.class);
        when(rate.getActive()).thenReturn(true);
        when(rate.getLivemode()).thenReturn(false);
        when(taxRates.retrieve("txr_test")).thenReturn(rate);
        Session result = mock(Session.class);
        when(result.getLivemode()).thenReturn(false);
        when(result.getId()).thenReturn("cs_test_123");
        when(result.getCustomer()).thenReturn("cus_test_123");
        when(result.getExpiresAt()).thenReturn(2_000_000_000L);
        when(result.getStatus()).thenReturn("open");
        when(result.getUrl()).thenReturn("https://checkout.stripe.test/session");
        when(sessions.create(any(SessionCreateParams.class), any(RequestOptions.class))).thenReturn(result);
        UUID account = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();

        assertEquals("cs_test_123", gateway.createCheckout(account, attempt, "cus_test_123",
                SubscriptionPlan.MONTHLY, "idem-1").sessionId());
        gateway.createCheckout(account, attempt, "cus_test_123", SubscriptionPlan.YEARLY, "idem-2");

        var params = ArgumentCaptor.forClass(SessionCreateParams.class);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(sessions, times(2)).create(params.capture(), options.capture());
        assertEquals("price_month", params.getAllValues().get(0).getLineItems().getFirst().getPrice());
        assertEquals("price_year", params.getAllValues().get(1).getLineItems().getFirst().getPrice());
        for (var p : params.getAllValues()) {
            assertEquals(SessionCreateParams.Mode.SUBSCRIPTION, p.getMode());
            assertEquals("cus_test_123", p.getCustomer());
            assertEquals(attempt.toString(), p.getClientReferenceId());
            assertEquals(account.toString(), p.getMetadata().get("timereport_account_id"));
            assertEquals("txr_test", p.getLineItems().getFirst().getTaxRates().getFirst());
            assertEquals(1L, p.getLineItems().getFirst().getQuantity());
            assertEquals("http://localhost:8080/?subscription=processing", p.getSuccessUrl());
        }
        assertEquals("idem-1", options.getAllValues().get(0).getIdempotencyKey());
        assertEquals("idem-2", options.getAllValues().get(1).getIdempotencyKey());
    }

    @Test
    void rejectsLiveKeysBeforeAnyStripeCall() {
        var gateway = gateway(true, "sk_live_must_not_be_used_in_stage_two");

        assertThrows(SubscriptionBillingUnavailable.class, gateway::requireCheckoutConfigured);
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
    void checkoutIsOffUntilExplicitlyEnabled() {
        assertThrows(SubscriptionBillingUnavailable.class, () -> gateway(false, "sk_test_example").requireCheckoutConfigured());
        assertDoesNotThrow(() -> gateway(false, "sk_test_example").requirePortalConfigured());
    }

    private StripeBillingGateway gateway(boolean enabled, String key) {
        return new StripeBillingGateway(enabled, Optional.of(key), Optional.of("price_month"),
                Optional.of("price_year"), Optional.of("txr_test"), Optional.of("bpc_test"),
                "http://localhost:8080") {
            @Override
            StripeClient client() {
                return client;
            }
        };
    }

    private Price price(String id, String interval) {
        Price price = mock(Price.class);
        Price.Recurring recurring = mock(Price.Recurring.class);
        when(price.getId()).thenReturn(id);
        when(price.getActive()).thenReturn(true);
        when(price.getLivemode()).thenReturn(false);
        when(price.getCurrency()).thenReturn("sek");
        when(price.getRecurring()).thenReturn(recurring);
        when(price.getUnitAmount()).thenReturn(9900L);
        when(recurring.getInterval()).thenReturn(interval);
        when(recurring.getIntervalCount()).thenReturn(1L);
        return price;
    }
}
