package com.artyomh.finance.domain.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record TransactionId(String value) {

    private static final int LENGTH = 16;
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{" + LENGTH + "}");
    // unit separator: "AB"+"C" and "A"+"BC" must not hash the same
    private static final String SEPARATOR = "\u001F";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    public TransactionId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid transaction id: " + value);
        }
    }

    public static TransactionId of(TransactionKey key, int occurrence) {
        Objects.requireNonNull(key, "key");
        if (occurrence < 0) {
            throw new IllegalArgumentException("occurrence must not be negative: " + occurrence);
        }
        String text = String.join(SEPARATOR,
                key.date().toString(),
                key.description(),
                key.direction().name(),
                key.money().amount().toPlainString(),
                key.money().currency().name(),
                Integer.toString(occurrence));
        return new TransactionId(sha256Hex(text).substring(0, LENGTH));
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
