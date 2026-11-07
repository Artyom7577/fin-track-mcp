package com.artyomh.finance.application.port;

import com.artyomh.finance.domain.transaction.Category;

import java.util.Map;

public interface CategoryOverrideRepository {

    Map<String, Category> findAll();

    void save(String merchant, Category category);
}
