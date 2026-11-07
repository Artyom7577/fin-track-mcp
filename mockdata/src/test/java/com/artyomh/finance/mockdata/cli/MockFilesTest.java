package com.artyomh.finance.mockdata.cli;

import com.artyomh.finance.mockdata.scenario.Scenarios;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MockFilesTest {

    private static final String REGENERATE = "./mvnw -q -pl mockdata -am compile exec:java "
            + "-Dexec.mainClass=com.artyomh.finance.mockdata.cli.GenerateGoldenSample";

    @TempDir
    private Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "csv"})
    void goldenSampleStillMatchesTheCommittedFiles(String extension) throws IOException {
        MockFiles.write(Scenarios.goldenSample(), GenerateGoldenSample.SEED, dir, GenerateGoldenSample.BASE_NAME);
        String name = GenerateGoldenSample.BASE_NAME + "." + extension;

        assertThat(Files.readAllBytes(dir.resolve(name)))
                .as("writer format changed; if intended, regenerate with: " + REGENERATE)
                .isEqualTo(golden(name));
    }

    @Test
    void defaultScenarioIsWrittenUnderTheExactCommittedNames() throws IOException {
        int lines = MockFiles.write(Scenarios.defaultSixMonths(), GenerateMockData.SEED, dir,
                GenerateMockData.BASE_NAME);

        assertThat(dir.resolve("mock-evoca-2026-05-to-10.xlsx")).isNotEmptyFile();
        assertThat(dir.resolve("mock-evoca-2026-05-to-10.csv")).isNotEmptyFile();
        assertThat(lines).isPositive();
    }

    @Test
    void refusesANonMockTarget() {
        assertRefused(dir, "evoca-october");
        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void refusesAMockNamedTargetAmongRealStatements() {
        Path real = dir.resolve("data").resolve("real");

        assertRefused(real, "mock-evoca");
        assertThat(real).doesNotExist();
    }

    @Test
    void refusesRealStatementsInAnyLetterCase() {
        assertRefused(dir.resolve("DATA").resolve("Real"), "mock-evoca");
    }

    @Test
    void refusesADataFolderThatLinksToRealStatements() throws IOException {
        Path real = Files.createDirectories(dir.resolve("elsewhere").resolve("data").resolve("real"));
        Path linkedData = Files.createSymbolicLink(dir.resolve("data"), real);

        assertRefused(linkedData, "mock-evoca");
        assertThat(real).isEmptyDirectory();
    }

    @Test
    void refusesTheHomeDataDirectory() {
        Path homeData = Path.of(System.getProperty("user.home"), ".fin-track-mcp", "statements");

        assertRefused(homeData, "mock-evoca");
    }

    @Test
    void refusesToWriteThroughASymbolicLink() throws IOException {
        Path statement = Files.writeString(dir.resolve("statement.csv"), "real");
        Files.createSymbolicLink(dir.resolve("mock-evoca.csv"), statement);

        assertRefused(dir, "mock-evoca");
        assertThat(statement).hasContent("real");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void requiresExactlyTheRootArgument(int count) {
        assertThatIllegalArgumentException().isThrownBy(() -> MockFiles.rootFrom(new String[count]));
    }

    private static void assertRefused(Path directory, String baseName) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MockFiles.write(Scenarios.goldenSample(), 1L, directory, baseName))
                .withMessage("Not a mock file; put real statements in ~/.fin-track-mcp/statements/");
    }

    private static byte[] golden(String name) throws IOException {
        try (InputStream in = MockFilesTest.class.getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("missing golden file " + name).isNotNull();
            return in.readAllBytes();
        }
    }
}
