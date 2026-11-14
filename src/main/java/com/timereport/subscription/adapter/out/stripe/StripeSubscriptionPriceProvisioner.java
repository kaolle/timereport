package com.timereport.subscription.adapter.out.stripe;

import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.model.TaxRate;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.ProductCreateParams;
import com.stripe.param.TaxRateCreateParams;
import com.timereport.subscription.application.port.out.SubscriptionPriceProvisioner;
import com.timereport.subscription.application.service.SubscriptionBillingUnavailable;
import com.timereport.subscription.domain.StripePriceReferences;
import com.timereport.subscription.domain.SubscriptionPlan;
import com.timereport.subscription.domain.SubscriptionPriceModel;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class StripeSubscriptionPriceProvisioner implements SubscriptionPriceProvisioner {
    private static final String MODEL_METADATA = "timereport_price_model_id";
    private final StripeBillingGateway gateway;

    public StripeSubscriptionPriceProvisioner(StripeBillingGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public StripePriceReferences provision(SubscriptionPriceModel model) {
        gateway.requirePricingConfigured();
        try {
            var product = gateway.client().v1().products().create(ProductCreateParams.builder()
                    .setName(model.name()).setActive(true)
                    .putMetadata(MODEL_METADATA, model.id().toString()).build(), options(model, "product"));
            validateProduct(product);
            var tax = gateway.client().v1().taxRates().create(TaxRateCreateParams.builder()
                    .setDisplayName("Moms").setCountry("SE").setActive(true)
                    .setInclusive(model.vatInclusive()).setPercentage(model.vatPercent())
                    .putMetadata(MODEL_METADATA, model.id().toString()).build(), options(model, "tax"));
            validateTax(tax, model);
            var recurring = PriceCreateParams.Recurring.builder()
                    .setInterval(model.plan() == SubscriptionPlan.MONTHLY
                            ? PriceCreateParams.Recurring.Interval.MONTH : PriceCreateParams.Recurring.Interval.YEAR)
                    .setIntervalCount(1L).build();
            var price = gateway.client().v1().prices().create(PriceCreateParams.builder()
                    .setActive(true).setCurrency("sek").setUnitAmount(model.amountMinor()).setProduct(product.getId())
                    .setRecurring(recurring).setTaxBehavior(model.vatInclusive()
                            ? PriceCreateParams.TaxBehavior.INCLUSIVE : PriceCreateParams.TaxBehavior.EXCLUSIVE)
                    .putMetadata(MODEL_METADATA, model.id().toString()).build(), options(model, "price"));
            validatePrice(price, product.getId(), model);
            return StripePriceReferences.builder().productId(product.getId()).priceId(price.getId())
                    .taxRateId(tax.getId()).build();
        } catch (StripeException failure) {
            throw new SubscriptionBillingUnavailable("Stripe kunde inte skapa prismodellen. Försök igen senare.", failure);
        }
    }

    private RequestOptions options(SubscriptionPriceModel model, String operation) {
        return RequestOptions.builder().setIdempotencyKey("timereport-price-model-" + model.id() + "-" + operation).build();
    }

    private void validateProduct(Product product) {
        if (product == null || !Boolean.FALSE.equals(product.getLivemode()) || !Boolean.TRUE.equals(product.getActive())
                || product.getId() == null || !product.getId().startsWith("prod_"))
            throw new SubscriptionBillingUnavailable("Stripe returnerade ingen aktiv testprodukt.");
    }

    private void validateTax(TaxRate tax, SubscriptionPriceModel model) {
        if (tax == null || !Boolean.FALSE.equals(tax.getLivemode()) || !Boolean.TRUE.equals(tax.getActive())
                || tax.getId() == null || !tax.getId().startsWith("txr_") || tax.getPercentage() == null
                || model.vatPercent().compareTo(tax.getPercentage()) != 0
                || !Boolean.valueOf(model.vatInclusive()).equals(tax.getInclusive()))
            throw new SubscriptionBillingUnavailable("Stripe returnerade ingen giltig testmomssats.");
    }

    private void validatePrice(Price price, String productId, SubscriptionPriceModel model) {
        String interval = model.plan() == SubscriptionPlan.MONTHLY ? "month" : "year";
        if (price == null || !Boolean.FALSE.equals(price.getLivemode()) || !Boolean.TRUE.equals(price.getActive())
                || price.getId() == null || !price.getId().startsWith("price_")
                || !productId.equals(price.getProduct()) || !"sek".equals(price.getCurrency())
                || !Long.valueOf(model.amountMinor()).equals(price.getUnitAmount())
                || !(model.vatInclusive() ? "inclusive" : "exclusive").equals(price.getTaxBehavior())
                || price.getRecurring() == null || !interval.equals(price.getRecurring().getInterval())
                || !Long.valueOf(1).equals(price.getRecurring().getIntervalCount()))
            throw new SubscriptionBillingUnavailable("Stripe returnerade inget giltigt återkommande testpris.");
    }
}
