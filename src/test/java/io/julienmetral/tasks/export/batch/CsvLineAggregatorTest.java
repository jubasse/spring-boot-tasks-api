package io.julienmetral.tasks.export.batch;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CsvLineAggregatorTest {

    @Test
    void everyValueIsQuotedAndSeparatedByAComma() {
        UUID id = UUID.fromString("0199a3c4-0000-7000-8000-000000000001");

        String line = CsvLineAggregator.line(List.of(id, "T-1", 42, Instant.parse("2030-01-02T03:04:05Z")));

        assertThat(line).isEqualTo("\"" + id + "\",\"T-1\",\"42\",\"2030-01-02T03:04:05Z\"");
    }

    @Test
    void nullAndEmptyTextAreBothAnEmptyValue() {
        assertThat(CsvLineAggregator.line(Arrays.asList("a", null, ""))).isEqualTo("\"a\",\"\",\"\"");
    }

    @Test
    void quotesAreDoubled() {
        assertThat(CsvLineAggregator.line(List.of("Say \"hi\""))).isEqualTo("\"Say \"\"hi\"\"\"");
    }

    @Test
    void commasAndLineBreaksStayInsideTheValue() {
        assertThat(CsvLineAggregator.line(List.of("one, two\r\nthree\nfour")))
                .isEqualTo("\"one, two\r\nthree\nfour\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "=1+1",
            "+1",
            "-1",
            "@SUM(A1:A2)",
            "\tcmd",
            "\rcmd",
            "\ncmd",
            "\uFF1D1+1",
            "\uFF0B1",
            "\uFF0D1",
            "\uFF20SUM(A1)"
    })
    void textASpreadsheetWouldReadAsAFormulaGetsALeadingApostrophe(String text) {
        assertThat(CsvLineAggregator.line(List.of(text))).isEqualTo("\"'" + text + "\"");
    }

    @Test
    void formulaWithQuotesIsNeutralisedAndItsQuotesDoubled() {
        String title = "=HYPERLINK(\"https://evil.example\",\"Open\")";

        assertThat(CsvLineAggregator.line(List.of(title)))
                .isEqualTo("\"'=HYPERLINK(\"\"https://evil.example\"\",\"\"Open\"\")\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=1", "1+1", "e-mail", "jane@example.com", " =1", "'quoted"})
    void formulaCharactersAfterTheFirstOneAreKept(String text) {
        assertThat(CsvLineAggregator.line(List.of(text))).isEqualTo("\"" + text + "\"");
    }

    @Test
    void anRfc4180ParserReadsTheValuesBack() throws IOException {
        List<String> values = List.of("T-1", "Title, with a comma", "Line one\r\nLine \"two\"", "=1+1", "");

        try (CSVParser parser = CSVFormat.RFC4180.parse(new StringReader(CsvLineAggregator.line(values)))) {
            List<CSVRecord> records = parser.getRecords();

            assertThat(records).singleElement().satisfies(record -> assertThat(record.values()).containsExactly(
                    "T-1", "Title, with a comma", "Line one\r\nLine \"two\"", "'=1+1", ""));
        }
    }

    @Test
    void aggregateWritesTheValuesTheFunctionExtracts() {
        CsvLineAggregator<Row> aggregator = new CsvLineAggregator<>(row -> List.of(row.reference(), row.title()));

        assertThat(aggregator.aggregate(new Row("T-7", "-urgent"))).isEqualTo("\"T-7\",\"'-urgent\"");
    }

    private record Row(String reference, String title) {
    }
}
