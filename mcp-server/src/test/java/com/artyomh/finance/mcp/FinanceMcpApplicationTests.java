package com.artyomh.finance.mcp;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class FinanceMcpApplicationTests {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("finance.storage.path", () -> dir.resolve("test.db").toString());
    }

    @Test
    void contextLoads() {
    }
}
