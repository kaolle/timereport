package com.timereport.invoicing.application.port.out;

import com.timereport.invoicing.domain.BillingCustomer;
import com.timereport.invoicing.domain.CustomerCodeBilling;
import com.timereport.invoicing.domain.SellerBillingProfile;

import java.util.List;
import java.util.Optional;

public interface InvoicingSettingsRepository {
    Optional<SellerBillingProfile> profile();
    void saveProfile(SellerBillingProfile profile);
    List<BillingCustomer> customers();
    void createCustomer(BillingCustomer customer);
    void updateCustomer(BillingCustomer customer);
    void deleteCustomer(BillingCustomer customer);
    List<CustomerCodeBilling> customerCodes();
    void saveCustomerCode(CustomerCodeBilling settings);
    void removeCustomerCode(String code);
}
