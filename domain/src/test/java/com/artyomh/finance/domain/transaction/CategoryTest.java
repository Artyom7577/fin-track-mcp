package com.artyomh.finance.domain.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTest {

    @Test
    void hasThreeIncomeAndThirteenExpenseCategories() {
        assertThat(Arrays.stream(Category.values()).filter(Category::isIncome)).hasSize(3);
        assertThat(Arrays.stream(Category.values()).filter(c -> !c.isIncome())).hasSize(13);
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void fallbackHasTheRequestedDirection(Direction direction) {
        assertThat(Category.fallbackFor(direction).direction()).isEqualTo(direction);
    }

    @Test
    void fallbacksAreOtherAndOtherIncome() {
        assertThat(Category.fallbackFor(Direction.EXPENSE)).isEqualTo(Category.OTHER);
        assertThat(Category.fallbackFor(Direction.INCOME)).isEqualTo(Category.OTHER_INCOME);
    }

    @ParameterizedTest
    @EnumSource(Category.class)
    void everyCategoryHasADisplayName(Category category) {
        assertThat(category.displayName()).isNotBlank();
    }
}
