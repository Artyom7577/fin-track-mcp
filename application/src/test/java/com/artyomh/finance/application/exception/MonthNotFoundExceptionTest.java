package com.artyomh.finance.application.exception;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonthNotFoundExceptionTest {

    @Test
    void messageListsTheAvailableRange() {
        MonthNotFoundException e = new MonthNotFoundException(YearMonth.of(2026, 3),
                List.of(YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 10)));

        assertThat(e.getMessage()).isEqualTo("No data for 2026-03. Available: 2026-05 to 2026-10");
        assertThat(e.code()).isEqualTo("MONTH_NOT_FOUND");
    }

    @Test
    void emptyDatabaseSaysNothingWasImported() {
        MonthNotFoundException e = new MonthNotFoundException(YearMonth.of(2026, 3), List.of());

        assertThat(e.getMessage()).isEqualTo("No statements imported yet; import a statement first.");
    }

    @Test
    void invalidInputHasItsOwnCode() {
        assertThat(new InvalidInputException("limit must be 1-50").code()).isEqualTo("INVALID_INPUT");
    }
}
