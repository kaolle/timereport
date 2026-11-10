package com.timereport.subscription.adapter.out.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.billingportal.SessionCreateParams;
import com.timereport.subscription.application.port.out.SubscriptionBillingGateway;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.HostedCheckout;
import com.timereport.subscription.domain.SubscriptionPlan;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class StripeBillingGateway implements SubscriptionBillingGateway {
    private final boolean enabled;
    private final String secretKey;
    private final String monthlyPrice;
    private final String yearlyPrice;
    private final String taxRate;
    private final String portalConfiguration;
    private final String publicUrl;
    private volatile StripeClient client;

    public StripeBillingGateway(@ConfigProperty(name = "subscription.checkout-enabled") boolean enabled,
                                @ConfigProperty(name = "subscription.stripe.secret-key") Optional<String> secretKey,
                                @ConfigProperty(name = "subscription.stripe.price-monthly") Optional<String> monthlyPrice,
                                @ConfigProperty(name = "subscription.stripe.price-yearly") Optional<String> yearlyPrice,
                                @ConfigProperty(name = "subscription.stripe.tax-rate") Optional<String> taxRate,
                                @ConfigProperty(name = "subscription.stripe.portal-configuration") Optional<String> portalConfiguration,
                                @ConfigProperty(name = "auth.public-url") String publicUrl) {
        this.enabled = enabled;
        this.secretKey = secretKey.orElse("");
        this.monthlyPrice = monthlyPrice.orElse("");
        this.yearlyPrice = yearlyPrice.orElse("");
        this.taxRate = taxRate.orElse("");
        this.portalConfiguration = portalConfiguration.orElse("");
        this.publicUrl = publicUrl;
    }

    @Override
    public void requireCheckoutConfigured() {
        if (!enabled || !secretKey.startsWith("sk_test_") || !monthlyPrice.startsWith("price_")
                || !yearlyPrice.startsWith("price_") || !taxRate.startsWith("txr_"))
            throw new SubscriptionBillingUnavailable("Abonnemangsköp är inte konfigurerade för Stripes testmiljö.");
        requireSafePublicUrl();
    }

    @Override
    public void requirePortalConfigured() {
        if (!secretKey.startsWith("sk_test_") || !portalConfiguration.startsWith("bpc_"))
            throw new SubscriptionBillingUnavailable("Stripe-portalen är inte konfigurerad för testmiljön.");
        requireSafePublicUrl();
    }

    private void requireSafePublicUrl() {
        URI uri;
        try {
            uri = URI.create(publicUrl);
        } catch (IllegalArgumentException invalid) {
            throw new SubscriptionBillingUnavailable("PUBLIC_URL måste vara appens säkra ursprung.");
        }
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "::1".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() != null && !uri.getPath().isEmpty() || publicUrl.endsWith("/")
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && local))
            throw new SubscriptionBillingUnavailable("PUBLIC_URL måste vara appens säkra ursprung.");
    }

    @Override
    public String createCustomer(UUID accountId, String email, String idempotencyKey) {
        try {
            var params = CustomerCreateParams.builder().setEmail(email)
                    .putMetadata("timereport_account_id", accountId.toString()).build();
            var options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
            var customer = client().v1().customers().create(params, options);
            if (Boolean.TRUE.equals(customer.getLivemode()) || customer.getId() == null)
                throw new SubscriptionBillingUnavailable("Stripe returnerade ingen testkund.");
            return customer.getId();
        } catch (StripeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public HostedCheckout createCheckout(UUID accountId, UUID attemptId, String customerId,
                                         SubscriptionPlan plan, String idempotencyKey) {
        try {
            var price = price(plan);
            validatePrice(price, plan);
            var rate = client().v1().taxRates().retrieve(taxRate);
            if (!Boolean.TRUE.equals(rate.getActive()) || !Boolean.FALSE.equals(rate.getLivemode()))
                throw new SubscriptionBillingUnavailable("Momsinställningen måste vara aktiv i Stripes testmiljö.");
            var item = com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                    .setPrice(price.getId()).setQuantity(1L).addTaxRate(taxRate).build();
            var subscription = com.stripe.param.checkout.SessionCreateParams.SubscriptionData.builder()
                    .putMetadata("timereport_account_id", accountId.toString()).build();
            var params = com.stripe.param.checkout.SessionCreateParams.builder()
                    .setMode(com.stripe.param.checkout.SessionCreateParams.Mode.SUBSCRIPTION)
                    .setUiMode(com.stripe.param.checkout.SessionCreateParams.UiMode.HOSTED_PAGE)
                    .setCustomer(customerId).setClientReferenceId(attemptId.toString())
                    .putMetadata("timereport_account_id", accountId.toString())
                    .setSubscriptionData(subscription).addLineItem(item)
                    .addAllowedPaymentMethodType(
                            com.stripe.param.checkout.SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .setBillingAddressCollection(
                            com.stripe.param.checkout.SessionCreateParams.BillingAddressCollection.REQUIRED)
                    .setLocale(com.stripe.param.checkout.SessionCreateParams.Locale.SV)
                    .setSuccessUrl(publicUrl + "/?subscription=processing")
                    .setCancelUrl(publicUrl + "/?subscription=cancelled")
                    .build();
            var options = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
            return fromSession(client().v1().checkout().sessions().create(params, options));
        } catch (StripeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public HostedCheckout retrieveCheckout(String sessionId) {
        try {
            return fromSession(client().v1().checkout().sessions().retrieve(sessionId));
        } catch (StripeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public String createPortal(String customerId) {
        requirePortalConfigured();
        try {
            var params = SessionCreateParams.builder().setCustomer(customerId)
                    .setConfiguration(portalConfiguration).setReturnUrl(publicUrl + "/?subscription=returned")
                    .setLocale(SessionCreateParams.Locale.SV).build();
            var options = RequestOptions.builder().setIdempotencyKey("timereport-portal-" + UUID.randomUUID()).build();
            var session = client().v1().billingPortal().sessions().create(params, options);
            if (!Boolean.FALSE.equals(session.getLivemode()) || !customerId.equals(session.getCustomer())
                    || session.getUrl() == null
                    || !session.getUrl().startsWith("https://"))
                throw new SubscriptionBillingUnavailable("Stripe returnerade ingen säker testportal.");
            return session.getUrl();
        } catch (StripeException e) {
            throw unavailable(e);
        }
    }

    private Price price(SubscriptionPlan plan) throws StripeException {
        return client().v1().prices().retrieve(plan == SubscriptionPlan.MONTHLY ? monthlyPrice : yearlyPrice);
    }

    private void validatePrice(Price price, SubscriptionPlan plan) {
        String interval = plan == SubscriptionPlan.MONTHLY ? "month" : "year";
        if (!Boolean.TRUE.equals(price.getActive()) || !Boolean.FALSE.equals(price.getLivemode())
                || !"sek".equals(price.getCurrency()) || price.getRecurring() == null
                || !interval.equals(price.getRecurring().getInterval())
                || !Long.valueOf(1).equals(price.getRecurring().getIntervalCount())
                || price.getUnitAmount() == null || price.getUnitAmount() <= 0)
            throw new SubscriptionBillingUnavailable("Stripe-priset måste vara aktivt, återkommande i SEK och i testläge.");
    }

    private HostedCheckout fromSession(Session session) {
        if (!Boolean.FALSE.equals(session.getLivemode()) || session.getId() == null
                || session.getCustomer() == null || session.getExpiresAt() == null)
            throw new SubscriptionBillingUnavailable("Stripe returnerade en ogiltig testköpsession.");
        HostedCheckout.State state = switch (session.getStatus()) {
            case "open" -> HostedCheckout.State.OPEN;
            case "complete" -> HostedCheckout.State.COMPLETE;
            case "expired" -> HostedCheckout.State.EXPIRED;
            default -> throw new SubscriptionBillingUnavailable("Stripe returnerade en okänd köpstatus.");
        };
        return HostedCheckout.builder().sessionId(session.getId()).customerId(session.getCustomer())
                .url(session.getUrl()).expiresAt(Instant.ofEpochSecond(session.getExpiresAt()))
                .state(state).build();
    }

    StripeClient client() {
        var current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = StripeClient.builder().setApiKey(secretKey)
                            .setConnectTimeout(5_000).setReadTimeout(15_000)
                            .setMaxNetworkRetries(2).build();
                    client = current;
                }
            }
        }
        return current;
    }

    private SubscriptionBillingUnavailable unavailable(StripeException cause) {
        return new SubscriptionBillingUnavailable("Stripe kunde inte slutföra begäran. Försök igen senare.", cause);
    }
}
