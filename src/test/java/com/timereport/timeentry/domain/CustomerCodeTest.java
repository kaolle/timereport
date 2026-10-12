package com.timereport.timeentry.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomerCodeTest {
    @Test
    void trimsCodeWithoutChangingItsCaseOrFormat() {
        assertEquals(new CustomerCode("FAIR1234"), CustomerCode.builder().value(" FAIR1234 ").build());
        assertEquals("customer-42", new CustomerCode("customer-42").value());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingCodesInConstructorAndBuilder(String value) {
        assertThrows(IllegalArgumentException.class, () -> new CustomerCode(value));
        assertThrows(IllegalArgumentException.class, () -> CustomerCode.builder().value(value).build());
    }
}
