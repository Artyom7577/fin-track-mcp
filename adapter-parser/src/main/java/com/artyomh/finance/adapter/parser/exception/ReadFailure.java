package com.artyomh.finance.adapter.parser.exception;

/**
 * Why a whole statement file could not be read; each value is also the stable error code.
 */
public enum ReadFailure {
    FILE_NOT_READABLE,
    FILE_DAMAGED,
    FILE_EMPTY,
    FILE_TOO_LARGE,
    LEGACY_XLS,
    WEB_PAGE,
    PDF_NOT_SUPPORTED,
    EXTENSION_MISMATCH,
    HEADER_NOT_FOUND,
    NOT_UTF8,
    MALFORMED_CSV,
    TOO_MANY_ROWS
}
