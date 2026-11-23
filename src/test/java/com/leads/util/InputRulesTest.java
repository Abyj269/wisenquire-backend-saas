package com.leads.util;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputRulesTest {
    @Test void rejectsNegativeAndOverPrecisionAmounts() {
        for (String value : new String[]{"-1", "0.001", "1000000000000"}) {
            assertThrows(IllegalArgumentException.class, () -> InputRules.amount(new BigDecimal(value), "Amount"));
        }
        assertDoesNotThrow(() -> InputRules.amount(new BigDecimal("999999999999.99"), "Amount"));
    }
    @Test void rejectsDiscountOverOneHundredPercent() {
        assertThrows(IllegalArgumentException.class, () -> InputRules.percentage(new BigDecimal("100.01"), "Discount"));
    }
    @Test void rejectsRemoteLogoAndHtmlAttachment() {
        assertThrows(IllegalArgumentException.class, () -> InputRules.logo("http://169.254.169.254/latest/meta-data/"));
        assertThrows(IllegalArgumentException.class, () -> InputRules.attachment("x.html", "data:text/html;base64,PHNjcmlwdD4="));
    }
    @Test void distinctCountryCodesDoNotMergeCustomers() {
        assertNotEquals(PhoneNumberUtil.canonical("+971501234567"), PhoneNumberUtil.canonical("+966501234567"));
        assertEquals(PhoneNumberUtil.canonical("00971501234567"), PhoneNumberUtil.canonical("+971 50 123 4567"));
    }
}
