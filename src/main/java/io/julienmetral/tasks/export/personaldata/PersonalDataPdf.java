package io.julienmetral.tasks.export.personaldata;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.julienmetral.tasks.export.ExportProperties;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The readable half of a personal data export: a Thymeleaf template rendered to XHTML, then to PDF by openhtmltopdf.
 * <p>
 * The renderer fetches nothing: every external resource is refused, before and after its URI is resolved, and the
 * fonts come from the jar. A template that referenced a URL would otherwise have the server fetch it. Every value is
 * written with {@code th:text}, which escapes it, so no text of a task or comment becomes markup.
 * <p>
 * Noto Sans is embedded (SIL Open Font License, {@code fonts/OFL.txt}): the PDF standard fonts cover Western
 * European letters only.
 */
@Component
public class PersonalDataPdf {

    private static final String FONT = "Noto Sans";

    private final TemplateEngine templates = new TemplateEngine();
    private final int maxRowsPerSection;

    public PersonalDataPdf(ExportProperties properties) {
        this.maxRowsPerSection = properties.pdfMaxRowsPerSection();

        // XML mode keeps the output well-formed XHTML, which is what the renderer parses
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/exports/");
        resolver.setSuffix(".xhtml");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        templates.setTemplateResolver(resolver);
    }

    /** @param data the content of {@code my-data.json}; each section of the PDF shows at most its first rows */
    public void render(Map<String, Object> data, Path output) throws IOException {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("data", data);
        context.setVariable("limit", maxRowsPerSection);

        String xhtml = templates.process("personal-data", context);

        try (OutputStream out = Files.newOutputStream(output)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();

            builder.useFastMode();
            builder.useFont(() -> font("NotoSans-Regular.ttf"), FONT, 400, FontStyle.NORMAL, true);
            builder.useFont(() -> font("NotoSans-Bold.ttf"), FONT, 700, FontStyle.NORMAL, true);
            builder.useExternalResourceAccessControl(
                    (uri, type) -> false, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.useExternalResourceAccessControl(
                    (uri, type) -> false, ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
        }
    }

    private static InputStream font(String file) {
        return PersonalDataPdf.class.getResourceAsStream("/fonts/" + file);
    }
}
