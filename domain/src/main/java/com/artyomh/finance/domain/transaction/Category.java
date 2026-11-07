package com.artyomh.finance.domain.transaction;

public enum Category {
    SALARY("Salary", Direction.INCOME),
    FREELANCE("Freelance", Direction.INCOME),
    OTHER_INCOME("Other income", Direction.INCOME),

    RENT("Rent & Housing", Direction.EXPENSE),
    UTILITIES("Utilities", Direction.EXPENSE),
    INTERNET_MOBILE("Internet & Mobile", Direction.EXPENSE),
    SUBSCRIPTIONS("Subscriptions", Direction.EXPENSE),
    GROCERIES("Groceries", Direction.EXPENSE),
    CAFES_RESTAURANTS("Cafes & Restaurants", Direction.EXPENSE),
    TRANSPORT("Transport", Direction.EXPENSE),
    HEALTH_FITNESS("Health & Fitness", Direction.EXPENSE),
    SHOPPING("Shopping", Direction.EXPENSE),
    TRAVEL("Travel", Direction.EXPENSE),
    CASH("Cash", Direction.EXPENSE),
    TRANSFERS("Transfers", Direction.EXPENSE),
    OTHER("Other", Direction.EXPENSE);

    private final String displayName;
    private final Direction direction;

    Category(String displayName, Direction direction) {
        this.displayName = displayName;
        this.direction = direction;
    }

    public String displayName() {
        return displayName;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isIncome() {
        return direction == Direction.INCOME;
    }

    public static Category fallbackFor(Direction direction) {
        return switch (direction) {
            case INCOME -> OTHER_INCOME;
            case EXPENSE -> OTHER;
        };
    }
}
