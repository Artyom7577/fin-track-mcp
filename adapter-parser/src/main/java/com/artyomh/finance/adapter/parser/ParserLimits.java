package com.artyomh.finance.adapter.parser;

/**
 * Caps on one statement file, the only size limits on import; each is about twice a measured 50,000-row mock export.
 */
public record ParserLimits(long maxXlsxBytes, long maxXlsxUnpackedBytes, long maxCsvBytes, int maxDataRows) {

    // measured: 50,000 rows are a 1,348,141-byte .xlsx whose parts unpack to 11,464,621 bytes, and a
    // 3,605,694-byte .csv; the .xlsx byte cap is tighter because POI holds the whole sheet (about 250 MB at 50,000)
    public static final ParserLimits DEFAULT = new ParserLimits(1_500_000L, 23_000_000L, 7_200_000L, 50_000);

    public ParserLimits {
        if (maxXlsxBytes <= 0 || maxXlsxUnpackedBytes <= 0 || maxCsvBytes <= 0 || maxDataRows <= 0) {
            throw new IllegalArgumentException("parser limits must be positive");
        }
    }
}
