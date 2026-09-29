package io.julienmetral.tasks.export.batch;

import org.springframework.batch.infrastructure.item.file.transform.LineAggregator;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One CSV line (RFC 4180): every value quoted, quotes doubled, null as an empty value.
 * <p>
 * A text that a spreadsheet would read as a formula (starting with {@code =}, {@code +}, {@code -}, {@code @}, a tab
 * or a line break, or their full-width forms) gets a leading apostrophe: a task title such as
 * {@code =HYPERLINK("https://evil.example")} would otherwise run when the export is opened (OWASP, CSV injection).
 * Spring Batch's {@code DelimitedLineAggregator} neither quotes nor escapes.
 */
final class CsvLineAggregator<T> implements LineAggregator<T> {

    private static final String FORMULA_STARTS = "=+-@\t\r\n＝＋－＠";

    private final Function<T, List<Object>> values;

    CsvLineAggregator(Function<T, List<Object>> values) {
        this.values = values;
    }

    @Override
    public String aggregate(T item) {
        return line(values.apply(item));
    }

    static String line(List<?> values) {
        return values.stream().map(CsvLineAggregator::field).collect(Collectors.joining(","));
    }

    private static String field(Object value) {
        if (value == null) {
            return "\"\"";
        }

        String text = value.toString();

        if (!text.isEmpty() && FORMULA_STARTS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }

        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
