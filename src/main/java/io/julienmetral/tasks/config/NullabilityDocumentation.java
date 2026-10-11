package io.julienmetral.tasks.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/**
 * Publishes the nullness of the application's records, which springdoc does not see: JSpecify's
 * {@code @Nullable} annotates the type ({@code TYPE_USE}), and swagger-core reads only annotations
 * on the declaration.
 *
 * <ul>
 *   <li>A {@code @Nullable} component allows {@code null}: {@code "null"} joins its types (and its
 *       enumeration), and a reference becomes {@code oneOf} the reference and {@code null}.
 *   <li>In a request, a component is required unless it is {@code @Nullable}.
 *   <li>In a response, every component is required: Jackson writes a null component as {@code
 *       null}, never leaves it out.
 * </ul>
 *
 * A record with {@code @JsonInclude} other than {@code ALWAYS} leaves its null components out:
 * those are optional and never {@code null}.
 */
class NullabilityDocumentation implements OpenApiCustomizer {

    private static final String BASE_PACKAGE = "io.julienmetral.tasks";

    private static final String COMPONENT_PREFIX = "#/components/schemas/";

    private static final String RESPONSE_PREFIX = "#/components/responses/";

    @Override
    @SuppressWarnings("rawtypes")
    public void customise(OpenAPI openApi) {
        // Scanned here rather than at startup: the bean exists in production, where the document
        // is off
        Map<String, List<Class<?>>> records = recordsBySimpleName();
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Set<String> inResponses = reachable(responseSchemas(openApi), schemas);
        Set<String> inRequests = reachable(requestSchemas(openApi), schemas);

        schemas.forEach(
                (name, schema) -> {
                    List<Class<?>> types = records.getOrDefault(name, List.of());

                    if (types.size() > 1) {
                        // springdoc names a schema after the simple name: two records would share
                        // it
                        throw new IllegalStateException(
                                "Records sharing the schema name %s: %s".formatted(name, types));
                    }
                    if (types.size() == 1 && schema.getProperties() != null) {
                        Class<?> type = types.getFirst();
                        document(
                                name,
                                schema,
                                type,
                                inResponses.contains(name),
                                inRequests.contains(name));
                    }
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void document(
            String name, Schema schema, Class<?> type, boolean inResponse, boolean inRequest) {
        boolean writesNulls = writesNulls(type);
        Set<String> required = new LinkedHashSet<>();

        if (schema.getRequired() != null) {
            required.addAll(schema.getRequired());
        }

        for (RecordComponent component : type.getRecordComponents()) {
            String property = component.getName();
            Schema propertySchema = (Schema) schema.getProperties().get(property);

            if (propertySchema == null) {
                continue;
            }

            boolean nullable = component.getAnnotatedType().isAnnotationPresent(Nullable.class);

            if (nullable && inResponse && inRequest && writesNulls) {
                // Required in the response and optional in the request: one schema cannot say both
                throw new IllegalStateException(
                        "%s is used by a request and a response and has the nullable component %s"
                                .formatted(name, property));
            }

            if (!nullable || (inResponse && writesNulls)) {
                required.add(property);
            }

            if (nullable && writesNulls) {
                schema.getProperties().put(property, allowNull(propertySchema));
            }
        }

        schema.setRequired(required.isEmpty() ? null : new ArrayList<>(required));
    }

    private static boolean writesNulls(Class<?> type) {
        JsonInclude include = type.getAnnotation(JsonInclude.class);

        return include == null
                || include.value() == JsonInclude.Include.ALWAYS
                || include.value() == JsonInclude.Include.USE_DEFAULTS;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema allowNull(Schema property) {
        if (property.get$ref() != null) {
            Schema wrapper =
                    new JsonSchema()
                            .oneOf(
                                    List.of(
                                            new JsonSchema().$ref(property.get$ref()),
                                            new JsonSchema().types(Set.of("null"))));
            wrapper.setDescription(property.getDescription());
            return wrapper;
        }

        if (property.getTypes() != null) {
            Set<String> types = new LinkedHashSet<>(property.getTypes());
            types.add("null");
            property.setTypes(types);
        }

        if (property.getEnum() != null && !property.getEnum().contains(null)) {
            List<Object> values = new ArrayList<>(property.getEnum());
            values.add(null);
            property.setEnum(values);
        }

        return property;
    }

    @SuppressWarnings("rawtypes")
    private static List<Schema> responseSchemas(OpenAPI openApi) {
        List<Schema> roots = new ArrayList<>();
        Map<String, ApiResponse> shared =
                Objects.requireNonNullElse(openApi.getComponents().getResponses(), Map.of());

        shared.values().forEach(response -> addContent(roots, response.getContent()));
        operations(openApi)
                .forEach(
                        operation -> {
                            if (operation.getResponses() == null) {
                                return;
                            }
                            operation
                                    .getResponses()
                                    .values()
                                    .forEach(
                                            response -> {
                                                String ref = response.get$ref();
                                                ApiResponse target =
                                                        ref != null
                                                                ? shared.get(
                                                                        ref.substring(
                                                                                RESPONSE_PREFIX
                                                                                        .length()))
                                                                : response;
                                                if (target != null) {
                                                    addContent(roots, target.getContent());
                                                }
                                            });
                        });
        return roots;
    }

    @SuppressWarnings("rawtypes")
    private static List<Schema> requestSchemas(OpenAPI openApi) {
        List<Schema> roots = new ArrayList<>();

        operations(openApi)
                .forEach(
                        operation -> {
                            RequestBody body = operation.getRequestBody();
                            if (body != null) {
                                addContent(roots, body.getContent());
                            }
                            if (operation.getParameters() != null) {
                                for (Parameter parameter : operation.getParameters()) {
                                    if (parameter.getSchema() != null) {
                                        roots.add(parameter.getSchema());
                                    }
                                }
                            }
                        });
        return roots;
    }

    private static List<Operation> operations(OpenAPI openApi) {
        List<Operation> operations = new ArrayList<>();

        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(item -> operations.addAll(item.readOperations()));
        }
        return operations;
    }

    @SuppressWarnings("rawtypes")
    private static void addContent(List<Schema> roots, @Nullable Content content) {
        if (content != null) {
            content.values().stream()
                    .map(mediaType -> mediaType.getSchema())
                    .filter(Objects::nonNull)
                    .forEach(roots::add);
        }
    }

    // The component schemas a set of schemas leads to, through references, properties, items and
    // compositions
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Set<String> reachable(List<Schema> roots, Map<String, Schema> schemas) {
        Set<String> names = new HashSet<>();
        Deque<Schema> pending = new ArrayDeque<>(roots);

        while (!pending.isEmpty()) {
            Schema schema = pending.pop();
            String ref = schema.get$ref();

            if (ref != null && ref.startsWith(COMPONENT_PREFIX)) {
                String name = ref.substring(COMPONENT_PREFIX.length());
                Schema target = schemas.get(name);
                if (names.add(name) && target != null) {
                    pending.push(target);
                }
            }
            if (schema.getProperties() != null) {
                pending.addAll(schema.getProperties().values());
            }
            if (schema.getItems() != null) {
                pending.push(schema.getItems());
            }
            if (schema.getAdditionalProperties() instanceof Schema additional) {
                pending.push(additional);
            }
            for (List<Schema> composed :
                    Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())) {
                if (composed != null) {
                    pending.addAll(composed);
                }
            }
        }
        return names;
    }

    private static Map<String, List<Class<?>>> recordsBySimpleName() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                (reader, factory) ->
                        Record.class
                                .getName()
                                .equals(reader.getClassMetadata().getSuperClassName()));

        Map<String, List<Class<?>>> records = new HashMap<>();
        ClassLoader classLoader = NullabilityDocumentation.class.getClassLoader();

        scanner.findCandidateComponents(BASE_PACKAGE)
                .forEach(
                        definition -> {
                            Class<?> type =
                                    ClassUtils.resolveClassName(
                                            Objects.requireNonNull(
                                                    definition.getBeanClassName(),
                                                    "A scanned class has a name"),
                                            classLoader);
                            records.computeIfAbsent(type.getSimpleName(), name -> new ArrayList<>())
                                    .add(type);
                        });
        return records;
    }
}
