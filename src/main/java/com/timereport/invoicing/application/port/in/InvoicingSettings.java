package com.timereport.invoicing.application.port.in;

import com.timereport.invoicing.domain.BillingCustomer;
import com.timereport.invoicing.domain.CustomerCodeBilling;
import com.timereport.invoicing.domain.SellerBillingProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoicingSettings {
    Optional<SellerBillingProfile> profile();
    SellerBillingProfile saveProfile(SellerBillingProfile profile);
    List<BillingCustomer> customers();
    BillingCustomer createCustomer(BillingCustomer customer);
    BillingCustomer updateCustomer(BillingCustomer customer);
    void deleteCustomer(UUID id);
    List<CustomerCodeBilling> customerCodes();
    CustomerCodeBilling saveCustomerCode(CustomerCodeBilling settings);
    void removeCustomerCode(String code);
}
