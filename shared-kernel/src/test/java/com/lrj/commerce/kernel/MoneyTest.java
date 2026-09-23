package com.lrj.commerce.kernel;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void normalizesExactlyWithoutBinaryFloatingPoint() {
        assertEquals(new Money(new BigDecimal("0.30")), new Money(new BigDecimal("0.1")).add(new Money(new BigDecimal("0.2"))));
        assertEquals(10, new Money(new BigDecimal("0.1000")).minorUnits());
        assertEquals("CNY", Money.ZERO.currency());
    }
    @Test void rejectsNegativePrecisionAndOverflow() {
        for (String input : new String[]{"-0.01", "0.001", "1000000000000", "1E-999999999", "1E+999999999"}) {
            assertThrows(DomainException.class, () -> new Money(new BigDecimal(input)), input);
        }
        assertThrows(DomainException.class, () -> new Money(new BigDecimal("999999999999.99")).add(Money.minor(1)));
        assertThrows(DomainException.class, () -> Money.ZERO.subtract(Money.minor(1)));
    }
    @Test void identifiersAreNotSilentlyNormalized() {
        assertEquals("tenant:A-1", Identifiers.require("tenant:A-1"));
        for (String value : new String[]{"", " a", "a/b", "a".repeat(65)}) {
            assertThrows(DomainException.class, () -> Identifiers.require(value));
        }
    }
}
