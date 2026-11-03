package com.artyomh.finance.domain.merchant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantNameCleanerTest {

    @ParameterizedTest
    @CsvSource({
            "POS 4578****1234 SAS SUPERMARKET YEREVAN AM, SAS SUPERMARKET",
            "pos   online  netflix.com,                   NETFLIX.COM",
            "ONLINE POS GLOVO,                            GLOVO",
            "SAS ****1234 GOURMET,                        SAS GOURMET",
            "457812******1234 ZARA GYUMRI AM,             ZARA",
            "ATM 4578123456789012 CASH,                   CASH",
            "UCOM PAYMENT,                                UCOM",
            "BEELINE TOP-UP AM,                           BEELINE",
            "ԵՐԵՎԱՆ ՍԻԹԻ,                                 ԵՐԵՎԱՆ ՍԻԹԻ",
            "POS 4578****1234,                            POS 4578****1234",
            "ATM 4578123456789012,                        ATM ****9012",
    })
    void stripsOperationPrefixesCardNumbersAndLocation(String description, String expected) {
        assertThat(MerchantNameCleaner.clean(description)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "POS 4578123456789012",
            "SAS 4578 1234 5678 9012 SUPERMARKET",
            "SAS 4578-1234-5678-9012",
            "CARD4578123456789012 ZARA",
            "ZARA \uFF14\uFF15\uFF17\uFF18\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16\uFF17\uFF18\uFF19\uFF10\uFF11\uFF12",
            "457812345678",
            "4578123456789012345",
    })
    void neverReturnsAFullCardNumber(String description) {
        String merchant = MerchantNameCleaner.clean(description);

        assertThat(merchant.replaceAll("[ -]", "")).doesNotContainPattern("(?U)\\d{5,}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"YANDEX GO 2026 10 05", "PHONE 091 123456"})
    void shortDigitGroupsAreKept(String description) {
        assertThat(MerchantNameCleaner.clean(description)).isEqualTo(description);
    }
}
