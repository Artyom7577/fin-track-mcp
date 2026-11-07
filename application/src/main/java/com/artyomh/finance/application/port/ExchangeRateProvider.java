package com.artyomh.finance.application.port;

import com.artyomh.finance.domain.money.Currency;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ExchangeRateProvider {
    BigDecimal rateToAmd(Currency currency, LocalDate date);
}