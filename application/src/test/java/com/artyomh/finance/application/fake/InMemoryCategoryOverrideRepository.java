package com.artyomh.finance.application.fake;

import com.artyomh.finance.application.port.CategoryOverrideRepository;
import com.artyomh.finance.domain.transaction.Category;

import java.util.HashMap;
import java.util.Map;

public final class InMemoryCategoryOverrideRepository implements CategoryOverrideRepository {

    private final Map<String, Category> overrides = new HashMap<>();

    @Override
    public Map<String, Category> findAll() {
        return Map.copyOf(overrides);
    }

    @Override
    public void save(String merchant, Category category) {
        overrides.put(merchant, category);
    }
}
