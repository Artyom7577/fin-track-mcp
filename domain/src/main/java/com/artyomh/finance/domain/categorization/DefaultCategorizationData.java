package com.artyomh.finance.domain.categorization;

import com.artyomh.finance.domain.transaction.Category;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * Built-in merchants and keywords; configured extras are checked before these, so custom rules win.
 */
public final class DefaultCategorizationData {

    private static final SequencedMap<String, Category> KNOWN_MERCHANTS = knownMerchantData();
    private static final SequencedMap<Category, List<String>> KEYWORDS = keywordData();

    private DefaultCategorizationData() {
    }

    public static SequencedMap<String, Category> knownMerchants() {
        return KNOWN_MERCHANTS;
    }

    public static SequencedMap<Category, List<String>> keywords() {
        return KEYWORDS;
    }

    private static SequencedMap<String, Category> knownMerchantData() {
        SequencedMap<String, Category> merchants = new LinkedHashMap<>();
        merchants.put("UCOM", Category.INTERNET_MOBILE);
        merchants.put("VIVA ARMENIA", Category.INTERNET_MOBILE);
        merchants.put("TEAM TELECOM", Category.INTERNET_MOBILE);
        merchants.put("APPLE.COM/BILL", Category.SUBSCRIPTIONS);
        merchants.put("YEREVAN CITY", Category.GROCERIES);
        merchants.put("BOOKING.COM", Category.TRAVEL);
        return Collections.unmodifiableSequencedMap(merchants);
    }

    private static SequencedMap<Category, List<String>> keywordData() {
        SequencedMap<Category, List<String>> keywords = new LinkedHashMap<>();
        keywords.put(Category.SALARY, List.of("SALARY", "PAYROLL"));
        keywords.put(Category.FREELANCE, List.of("UPWORK", "FIVERR", "DEEL"));
        keywords.put(Category.RENT, List.of("RENT"));
        keywords.put(Category.UTILITIES, List.of("ENA", "VEOLIA", "GAZPROM"));
        keywords.put(Category.SUBSCRIPTIONS, List.of("NETFLIX", "SPOTIFY", "ICLOUD", "OPENAI", "YOUTUBE PREMIUM"));
        keywords.put(Category.GROCERIES, List.of("SUPERMARKET", "SAS", "CARREFOUR"));
        keywords.put(Category.CAFES_RESTAURANTS,
                List.of("CAFE", "COFFEE", "COFFEESHOP", "PIZZA", "RESTAURANT", "BOLT FOOD"));
        keywords.put(Category.TRANSPORT, List.of("YANDEX GO", "TAXI", "BOLT", "PETROL", "FUEL"));
        keywords.put(Category.HEALTH_FITNESS, List.of("GYM", "PHARM", "PHARMACY", "APTEKA", "CLINIC"));
        keywords.put(Category.SHOPPING, List.of("WILDBERRIES", "ZARA", "ELECTRONICS", "AMAZON"));
        keywords.put(Category.TRAVEL, List.of("HOTEL", "AIRLINES", "AIRBNB"));
        keywords.put(Category.CASH, List.of("ATM"));
        return Collections.unmodifiableSequencedMap(keywords);
    }
}
