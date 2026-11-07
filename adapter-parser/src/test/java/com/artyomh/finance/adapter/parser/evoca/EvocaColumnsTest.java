package com.artyomh.finance.adapter.parser.evoca;

import com.artyomh.finance.adapter.parser.evoca.EvocaColumns.Column;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvocaColumnsTest {

    @Test
    void matchesBilingualLabelsInAnyOrderWithExtraColumns() {
        EvocaColumns columns = EvocaColumns.locate(List.of("Note", "Արժույթ / Currency", "Ամսաթիվ / Date",
                "Մուտք / Credit", "Ելք / Debit", "Նկարագրություն / Description")).orElseThrow();

        assertThat(columns.position(Column.CURRENCY)).isEqualTo(1);
        assertThat(columns.position(Column.DATE)).isEqualTo(2);
        assertThat(columns.position(Column.DEBIT)).isEqualTo(4);
    }

    @Test
    void matchesRussianAndOddlySpacedOrCasedLabels() {
        assertThat(EvocaColumns.isHeader(List.of("ДАТА", " Описание ", "Расход", "Приход", "Валюта"))).isTrue();
        assertThat(EvocaColumns.isHeader(List.of("Date ", "DESCRIPTION", "дебет", "кредит", "ԱՐԺՈՒՅԹ"))).isTrue();
    }

    @Test
    void balanceIsOptional() {
        assertThat(EvocaColumns.isHeader(List.of("Date", "Description", "Debit", "Credit", "Currency", "Balance")))
                .isTrue();
        assertThat(EvocaColumns.isHeader(List.of("Date", "Description", "Debit", "Credit"))).isFalse();
    }

    @Test
    void aColumnMatchingTwiceDisqualifiesTheRow() {
        assertThat(EvocaColumns.isHeader(List.of("Date", "Дата", "Description", "Debit", "Credit", "Currency")))
                .isFalse();
        assertThat(EvocaColumns.isHeader(List.of("Date", "Description", "Debit / Credit", "Currency"))).isFalse();
    }

    @Test
    void partsMustMatchWholeNotAsSubstrings() {
        assertThat(EvocaColumns.isHeader(List.of("Value date", "Description", "Debit", "Credit", "Currency")))
                .isFalse();
    }

    @Test
    void expectedListsTheRequiredColumnsInBothLanguages() {
        assertThat(EvocaColumns.expected()).isEqualTo("Date (Ամսաթիվ), Description (Նկարագրություն), Debit (Ելք), "
                + "Credit (Մուտք), Currency (Արժույթ)");
    }
}
