package com.example.aichat.feature.ultra

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class UltraPricingTest {
    @Test fun regionalAmountsRespectCurrencyMinorUnits() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertThat(formatUltraPrice("USD", 1399)).contains("13.99")
            assertThat(formatUltraPrice("AUD", 1999)).contains("19.99")
            assertThat(formatUltraPrice("JPY", 1499)).contains("1,499")
            assertThat(formatUltraPrice("INR", 39900)).contains("399.00")
        } finally { Locale.setDefault(previous) }
    }
}
