package com.artyomh.finance.application.port;

import com.artyomh.finance.application.importing.ParseResult;

import java.nio.file.Path;

public interface StatementParser {
    ParseResult parse(Path file);
}
