package com.artyomh.finance.mockdata.scenario;

import java.util.Objects;

public record Payee(String name, Channel channel) {

    public Payee {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(channel, "channel");
        if (name.isBlank()) {
            throw new IllegalArgumentException("payee name must not be blank");
        }
    }
}
