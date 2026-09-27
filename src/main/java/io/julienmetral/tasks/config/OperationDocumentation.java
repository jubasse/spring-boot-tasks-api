package io.julienmetral.tasks.config;

import io.julienmetral.tasks.shared.exceptions.ProblemType;
import io.julienmetral.tasks.shared.openapi.DocumentedProblems;
import io.julienmetral.tasks.shared.openapi.RateLimited;
import io.julienmetral.tasks.shared.security.AccessDescription;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.julienmetral.tasks.config.OpenApiConfiguration.CONTENT_TOO_LARGE;
import static io.julienmetral.tasks.config.OpenApiConfiguration.FORBIDDEN;
import static io.julienmetral.tasks.config.OpenApiConfiguration.SERVICE_UNAVAILABLE;
import static io.julienmetral.tasks.config.OpenApiConfiguration.TOO_MANY_REQUESTS;
import static io.julienmetral.tasks.config.OpenApiConfiguration.UNSUPPORTED_MEDIA_TYPE;
import static io.julienmetral.tasks.config.OpenApiConfiguration.responseReference;
import static io.julienmetral.tasks.config.ProblemDocumentation.PROBLEM;
import static io.julienmetral.tasks.config.ProblemDocumentation.VALIDATION_PROBLEM;
import static io.julienmetral.tasks.config.ProblemDocumentation.header;
import static io.julienmetral.tasks.config.ProblemDocumentation.object;
import static io.julienmetral.tasks.config.ProblemDocumentation.response;
import static io.julienmetral.tasks.config.ProblemDocumentation.string;

/**
 * Documents what the annotations of an operation's method say: its access rule ({@link AccessDescription} of its
 * security annotation) with a 403, its typed problems ({@link DocumentedProblems}), the problems of a file upload, a
 * 429 ({@link RateLimited}) and the Location header of a 201.
 */
class OperationDocumentation implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        describeAccess(operation, handlerMethod);
        documentProblems(operation, handlerMethod);

        if (handlerMethod.hasMethodAnnotation(RateLimited.class)) {
            responses(operation).putIfAbsent("429", reference(TOO_MANY_REQUESTS));
        }

        documentLocation(operation, handlerMethod);
        documentMultipartForm(operation, handlerMethod);

        return operation;
    }

    /** The access rule of each security annotation of the method, as documented by its {@link AccessDescription}. */
    static List<String> accessRules(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethod().getAnnotations())
                .map(OperationDocumentation::describe)
                .flatMap(Optional::stream)
                .toList();
    }

    private static void describeAccess(Operation operation, HandlerMethod handlerMethod) {
        List<String> rules = accessRules(handlerMethod);

        if (rules.isEmpty()) {
            return;
        }

        String access = "**Access:** " + String.join(" ", rules);
        operation.setDescription(operation.getDescription() == null
                ? access
                : operation.getDescription() + "\n\n" + access);
        responses(operation).putIfAbsent("403", reference(FORBIDDEN));
    }

    private static Optional<String> describe(Annotation annotation) {
        AccessDescription description = annotation.annotationType().getAnnotation(AccessDescription.class);

        if (description == null) {
            return Optional.empty();
        }

        String text = description.value();
        for (Map.Entry<String, Object> attribute : AnnotationUtils.getAnnotationAttributes(annotation).entrySet()) {
            text = text.replace("{" + attribute.getKey() + "}", render(attribute.getValue()));
        }
        return Optional.of(text);
    }

    private static String render(Object value) {
        Object[] values = value instanceof Object[] array ? array : new Object[]{value};

        return Arrays.stream(values)
                .map(item -> item instanceof Enum<?> constant ? constant.name() : String.valueOf(item))
                .collect(Collectors.joining(" or "));
    }

    private static void documentProblems(Operation operation, HandlerMethod handlerMethod) {
        List<ProblemType> types = new ArrayList<>();

        DocumentedProblems documented = handlerMethod.getMethodAnnotation(DocumentedProblems.class);
        if (documented != null) {
            types.addAll(List.of(documented.value()));
        }

        if (takesFiles(handlerMethod)) {
            types.add(ProblemType.INFECTED_FILE);
            ApiResponses responses = responses(operation);
            responses.putIfAbsent("413", reference(CONTENT_TOO_LARGE));
            responses.putIfAbsent("415", reference(UNSUPPORTED_MEDIA_TYPE));
            responses.putIfAbsent("503", reference(SERVICE_UNAVAILABLE));
        }

        Map<HttpStatus, List<ProblemType>> byStatus = types.stream()
                .distinct()
                .collect(Collectors.groupingBy(ProblemType::status, LinkedHashMap::new, Collectors.toList()));

        byStatus.forEach((status, newTypes) -> {
            // Two handlers can share one operation (the JSON and the multipart comment): keep what the other added
            List<ProblemType> statusTypes = Stream.concat(
                    documentedTypes(responses(operation).get(String.valueOf(status.value()))).stream(),
                    newTypes.stream()
            ).distinct().toList();

            // A 400 is always also a possible validation-error, so its examples start with one
            if (status == HttpStatus.BAD_REQUEST) {
                List<ProblemType> withValidation = Stream.concat(
                        Stream.of(ProblemType.VALIDATION_ERROR),
                        statusTypes.stream().filter(type -> type != ProblemType.VALIDATION_ERROR)
                ).toList();
                responses(operation).put("400", response(
                        "Invalid request: see errors for each invalid value, or type for another problem.",
                        VALIDATION_PROBLEM,
                        withValidation));
                return;
            }

            String titles = statusTypes.stream().map(ProblemType::title).collect(Collectors.joining(", or "));
            responses(operation).put(
                    String.valueOf(status.value()),
                    response(status.getReasonPhrase() + ": " + titles + ".", PROBLEM, statusTypes));
        });
    }

    private static List<ProblemType> documentedTypes(ApiResponse response) {
        if (response == null || response.getContent() == null) {
            return List.of();
        }

        io.swagger.v3.oas.models.media.MediaType problem = response.getContent().get(ProblemDocumentation.PROBLEM_JSON);
        if (problem == null || problem.getExamples() == null) {
            return List.of();
        }

        return Arrays.stream(ProblemType.values())
                .filter(type -> problem.getExamples().containsKey(type.slug()))
                .toList();
    }

    private static boolean takesFiles(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch(parameter -> parameter.hasParameterAnnotation(RequestPart.class) || isFile(parameter));
    }

    private static boolean isFile(MethodParameter parameter) {
        return MultipartFile.class.isAssignableFrom(parameter.getParameterType())
                || MultipartFile.class.equals(parameter.nested().getNestedParameterType());
    }

    private static void documentLocation(Operation operation, HandlerMethod handlerMethod) {
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(),
                ResponseStatus.class);
        ApiResponse created = responses(operation).get("201");

        if (status != null && status.code() == HttpStatus.CREATED && created != null) {
            created.addHeaderObject("Location", header(
                    "URL of the created resource.",
                    string().format("uri-reference")));
        }
    }

    // springdoc documents a multipart form bound to a @ModelAttribute object without its @RequestPart files, so the
    // form is rebuilt from both: the object's fields, then one property per part
    private static void documentMultipartForm(Operation operation, HandlerMethod handlerMethod) {
        MethodParameter[] parameters = handlerMethod.getMethodParameters();
        Optional<MethodParameter> form = Arrays.stream(parameters)
                .filter(parameter -> parameter.hasParameterAnnotation(ModelAttribute.class))
                .findFirst();
        List<MethodParameter> parts = Arrays.stream(parameters)
                .filter(parameter -> parameter.hasParameterAnnotation(RequestPart.class))
                .toList();

        if (form.isEmpty() || parts.isEmpty() || operation.getRequestBody() == null) {
            return;
        }

        io.swagger.v3.oas.models.media.MediaType multipart = operation.getRequestBody().getContent()
                .get(org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE);
        if (multipart == null) {
            return;
        }

        JsonSchema files = object();
        for (MethodParameter part : parts) {
            RequestPart annotation = part.getParameterAnnotation(RequestPart.class);
            String name = annotation != null && !annotation.name().isEmpty()
                    ? annotation.name()
                    : part.getParameterName();
            JsonSchema binary = (JsonSchema) string().format("binary");
            files.addProperty(name, Collection.class.isAssignableFrom(part.getParameterType())
                    ? new JsonSchema().types(Set.of("array")).items(binary)
                    : binary);
        }

        multipart.setSchema(new JsonSchema().allOf(List.of(
                ProblemDocumentation.reference(form.get().getParameterType().getSimpleName()),
                files)));
    }

    private static ApiResponses responses(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        return operation.getResponses();
    }

    static ApiResponse reference(String response) {
        return new ApiResponse().$ref(responseReference(response));
    }
}
