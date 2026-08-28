package app.pickple.docs;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * springdoc 이 만든 OpenAPI 스펙을 LLM 이 읽기 좋은 마크다운으로 옮긴다.
 *
 * <p>컨트롤러를 직접 스캔하지 않는다. 스캔은 springdoc 이 하고 이 클래스는 그 결과를
 * 마크다운으로 <b>직렬화만</b> 한다. 그래서 컨트롤러를 고치고 재기동하면 출력이 저절로 따라오며
 * 문서와 코드가 어긋날 여지가 구조적으로 없다. 근거: ADR-0011.
 *
 * <p><b>Jackson 주의</b> — 이 클래스가 다루는 {@link JsonNode} 는
 * {@code com.fasterxml.jackson}(Jackson 2)이다. 이 프로젝트에는 Spring Boot 4 가 쓰는
 * {@code tools.jackson}(Jackson 3)도 함께 올라와 있고 두 라이브러리의 클래스 이름이 같아서
 * import 를 잘못 써도 컴파일이 통과한다. springdoc 이 Jackson 2 로 직렬화하므로 여기서는
 * Jackson 2 를 쓴다. 스프링이 관리하는 {@code ObjectMapper} 빈을 주입하면 Jackson 3 이 오므로
 * 주입하지 않는다.
 *
 * <p>스프링에 의존하지 않는 순수 클래스다. 덕분에 가장 위험한 부분(순환 참조 전개)을
 * 컨테이너 없이 테스트할 수 있다.
 */
public class OpenApiMarkdownRenderer {

    /**
     * {@code $ref} 없이 익명 객체가 무한히 중첩되는 경우를 끊는다.
     *
     * <p>{@code visited} 집합은 <b>이름 있는</b> 순환만 잡는다. 이름이 없는 중첩은 걸리지 않으므로
     * 독립된 두 번째 안전벨트가 필요하다. 둘은 역할이 다르며 하나로 합칠 수 없다.
     */
    private static final int MAX_DEPTH = 8;

    private static final List<String> METHODS =
            List.of("get", "post", "put", "patch", "delete", "head", "options");

    public String render(JsonNode oas) {
        return render(oas, null);
    }

    /**
     * @param tagFilter null 이면 전체, 값이 있으면 해당 태그의 오퍼레이션만 렌더한다.
     *                  태그별 엔드포인트는 아직 노출하지 않지만(현재 태그가 1개뿐이라 전체와 같다)
     *                  필요해질 때 컨트롤러만 추가하면 되도록 렌더러는 미리 받아 둔다.
     */
    String render(JsonNode oas, String tagFilter) {
        StringBuilder out = new StringBuilder();
        appendHeader(out, oas);

        Map<String, List<Operation>> byTag = collectByTag(oas, tagFilter);
        Map<String, String> tagDescriptions = collectTagDescriptions(oas);

        for (Map.Entry<String, List<Operation>> entry : byTag.entrySet()) {
            String tag = entry.getKey();
            out.append("\n## ").append(tag);
            String description = tagDescriptions.get(tag);
            if (description != null && !description.isBlank()) {
                out.append(" — ").append(description);
            }
            out.append("\n");

            for (Operation operation : entry.getValue()) {
                appendOperation(out, operation, oas);
            }
        }
        return out.toString();
    }

    private void appendHeader(StringBuilder out, JsonNode oas) {
        JsonNode info = oas.path("info");
        String title = text(info.path("title"), "API");
        out.append("# ").append(title).append('\n');

        String version = text(info.path("version"), null);
        if (version != null) {
            out.append("버전: ").append(version).append('\n');
        }

        // 모든 응답이 같은 봉투로 감싸이므로 여기서 한 번만 적는다.
        // 오퍼레이션마다 반복하면 같은 3필드가 문서 전체에 복제돼 토큰만 먹는다.
        out.append('\n')
                .append("모든 응답은 다음 봉투로 감싸인다. 아래 각 응답 설명은 returnObject 안쪽이다.\n")
                .append("  {\"code\": \"OK\", \"message\": \"정상 처리되었습니다.\", \"returnObject\": <T>}\n")
                .append("인증이 필요한 요청에는 Authorization: Bearer {accessToken} 헤더를 보낸다.\n");
    }

    private Map<String, List<Operation>> collectByTag(JsonNode oas, String tagFilter) {
        Map<String, List<Operation>> byTag = new LinkedHashMap<>();
        JsonNode paths = oas.path("paths");

        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            JsonNode pathItem = paths.path(path);

            for (String method : METHODS) {
                JsonNode op = pathItem.path(method);
                if (op.isMissingNode()) {
                    continue;
                }
                String tag = firstTag(op);
                if (tagFilter != null && !tagFilter.equalsIgnoreCase(tag)) {
                    continue;
                }
                byTag.computeIfAbsent(tag, k -> new ArrayList<>())
                        .add(new Operation(method, path, op));
            }
        }
        return byTag;
    }

    private Map<String, String> collectTagDescriptions(JsonNode oas) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (JsonNode tag : oas.path("tags")) {
            descriptions.put(text(tag.path("name"), ""), text(tag.path("description"), ""));
        }
        return descriptions;
    }

    private String firstTag(JsonNode op) {
        JsonNode tags = op.path("tags");
        return tags.isArray() && !tags.isEmpty() ? tags.get(0).asText() : "기타";
    }

    private void appendOperation(StringBuilder out, Operation operation, JsonNode oas) {
        JsonNode op = operation.node();

        out.append("\n### ").append(operation.method().toUpperCase())
                .append(' ').append(operation.path());
        String summary = text(op.path("summary"), null);
        if (summary != null) {
            out.append(" — ").append(summary);
        }
        out.append('\n');

        String description = text(op.path("description"), null);
        if (description != null) {
            out.append(description).append('\n');
        }

        appendParameters(out, op, oas);
        appendRequestBody(out, op, oas);
        appendResponses(out, op, oas);
    }

    private void appendParameters(StringBuilder out, JsonNode op, JsonNode oas) {
        JsonNode parameters = op.path("parameters");
        if (!parameters.isArray() || parameters.isEmpty()) {
            return;
        }
        out.append("파라미터:\n");
        for (JsonNode parameter : parameters) {
            out.append("  ").append(text(parameter.path("name"), "?"))
                    .append(" (").append(text(parameter.path("in"), "query")).append(") ")
                    .append(typeOf(parameter.path("schema"), oas))
                    .append(parameter.path("required").asBoolean(false) ? " 필수" : " 선택");
            String description = text(parameter.path("description"), null);
            if (description != null) {
                out.append(" — ").append(description);
            }
            out.append('\n');
        }
    }

    private void appendRequestBody(StringBuilder out, JsonNode op, JsonNode oas) {
        JsonNode schema = jsonSchema(op.path("requestBody"));
        if (schema.isMissingNode()) {
            return;
        }
        out.append("요청 본문:\n");
        out.append(renderSchema(schema, oas, new LinkedHashSet<>(), 0, "  "));
    }

    private void appendResponses(StringBuilder out, JsonNode op, JsonNode oas) {
        JsonNode responses = op.path("responses");
        if (responses.isMissingNode()) {
            return;
        }
        for (Iterator<String> it = responses.fieldNames(); it.hasNext(); ) {
            String status = it.next();
            JsonNode response = responses.path(status);
            JsonNode schema = jsonSchema(response);

            out.append("응답 ").append(status);
            String description = text(response.path("description"), null);
            if (description != null && !description.isBlank()) {
                out.append(" — ").append(description);
            }
            out.append(":\n");

            if (schema.isMissingNode()) {
                out.append("  (본문 없음)\n");
            } else {
                out.append(renderSchema(unwrapEnvelope(schema, oas), oas, new LinkedHashSet<>(), 0, "  "));
            }
        }
    }

    /**
     * {@code ApiResponse} 봉투를 벗기고 {@code returnObject} 안쪽만 남긴다.
     *
     * <p>모든 응답이 {@code code}/{@code message}/{@code returnObject} 로 감싸이는데,
     * 그 세 필드를 오퍼레이션마다 반복하면 같은 내용이 문서 전체에 복제된다.
     * 봉투 설명은 문서 머리에 한 번만 두고 여기서는 알맹이만 보여준다.
     *
     * <p>봉투 모양이 아니면(래핑하지 않는 응답) 원본을 그대로 돌려준다.
     */
    private JsonNode unwrapEnvelope(JsonNode schema, JsonNode oas) {
        JsonNode resolved = schema;
        String ref = text(schema.path("$ref"), null);
        if (ref != null) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            resolved = oas.path("components").path("schemas").path(name);
        }
        JsonNode properties = resolved.path("properties");
        if (properties.has("code") && properties.has("message") && properties.has("returnObject")) {
            return properties.path("returnObject");
        }
        return schema;
    }

    /** {@code requestBody}/{@code response} 에서 JSON 스키마를 꺼낸다. 없으면 missing 노드. */
    private JsonNode jsonSchema(JsonNode holder) {
        JsonNode content = holder.path("content");
        JsonNode json = content.path("application/json");
        if (!json.isMissingNode()) {
            return json.path("schema");
        }
        // application/json 이 아니어도 첫 미디어 타입을 쓴다. 없는 것보다 낫다.
        Iterator<String> types = content.fieldNames();
        return types.hasNext() ? content.path(types.next()).path("schema") : content.path("__absent__");
    }

    /**
     * 스키마를 재귀적으로 펼친다. 이 메서드가 이 클래스의 실질이자 유일한 실질 위험이다.
     *
     * <p>OAS 는 스키마를 {@code #/components/schemas/X} 로 정규화해 두는데, LLM 에게는
     * 그 참조를 펼쳐 한자리에 보여줘야 쓸모가 있다. 펼치다 보면 자기 자신으로 돌아오는
     * 스키마에서 무한 재귀가 난다.
     *
     * @param visited 현재 전개 <b>경로</b>에 있는 스키마 이름들. 전역 누적이 아니라
     *                되돌리기(backtracking)를 해야 한다. 전역으로 두면 형제 필드가 같은 타입을
     *                두 번 참조하는 정상적인 경우(billing·shipping 이 둘 다 Address)를
     *                순환으로 오인해 조용히 잘라먹는다. 조상만 막고 형제는 통과시킨다.
     */
    private String renderSchema(JsonNode schema, JsonNode oas, Set<String> visited, int depth, String indent) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return indent + "(정의 없음)\n";
        }
        if (depth > MAX_DEPTH) {
            return indent + "… (깊이 제한)\n";
        }

        // $ref 은 다른 키워드보다 먼저 본다. OAS 3.1 은 $ref 옆에 description 을 둘 수 있다.
        String ref = text(schema.path("$ref"), null);
        if (ref != null) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            if (visited.contains(name)) {
                return indent + "↻ " + name + " (순환 참조 — 위 정의와 같다)\n";
            }
            JsonNode resolved = oas.path("components").path("schemas").path(name);
            if (resolved.isMissingNode()) {
                return indent + name + " (정의 없음)\n";
            }
            visited.add(name);
            try {
                return renderSchema(resolved, oas, visited, depth + 1, indent);
            } finally {
                visited.remove(name);   // ★ 되돌리기 — 형제 참조까지 막지 않는다
            }
        }

        JsonNode allOf = schema.path("allOf");
        if (allOf.isArray() && !allOf.isEmpty()) {
            StringBuilder merged = new StringBuilder();
            for (JsonNode part : allOf) {
                merged.append(renderSchema(part, oas, visited, depth + 1, indent));
            }
            return merged.toString();
        }

        for (String keyword : List.of("oneOf", "anyOf")) {
            JsonNode branches = schema.path(keyword);
            if (branches.isArray() && !branches.isEmpty()) {
                StringBuilder out = new StringBuilder(indent + "다음 중 하나:\n");
                for (JsonNode branch : branches) {
                    out.append(indent).append("  -\n")
                            .append(renderSchema(branch, oas, visited, depth + 1, indent + "    "));
                }
                return out.toString();
            }
        }

        String type = typeOf(schema, oas);
        JsonNode items = schema.path("items");
        if ("array".equals(type) || !items.isMissingNode()) {
            return indent + "배열:\n" + renderSchema(items, oas, visited, depth + 1, indent + "  ");
        }

        JsonNode properties = schema.path("properties");
        if (!properties.isMissingNode() && !properties.isEmpty()) {
            Set<String> required = new LinkedHashSet<>();
            for (JsonNode name : schema.path("required")) {
                required.add(name.asText());
            }
            StringBuilder out = new StringBuilder();
            for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                JsonNode property = properties.path(name);
                out.append(indent).append(name)
                        .append("  ").append(typeOf(property, oas))
                        .append("  ").append(required.contains(name) ? "필수" : "선택");
                String description = text(property.path("description"), null);
                if (description != null) {
                    out.append("  — ").append(description);
                }
                out.append('\n');

                // 중첩 객체·배열·$ref 는 한 단계 들여써서 펼친다.
                if (isExpandable(property)) {
                    out.append(renderSchema(property, oas, visited, depth + 1, indent + "  "));
                }
            }
            return out.toString();
        }

        return indent + type + enumSuffix(schema) + '\n';
    }

    /** 스칼라는 이미 한 줄로 다 적었으므로 더 펼치지 않는다. */
    private boolean isExpandable(JsonNode schema) {
        return schema.has("$ref")
                || schema.has("allOf") || schema.has("oneOf") || schema.has("anyOf")
                || !schema.path("items").isMissingNode()
                || !schema.path("properties").isMissingNode();
    }

    /**
     * OAS 3.1 은 {@code type} 대신 {@code types} 배열을 쓰기도 한다.
     * {@code type} 만 읽으면 모든 필드가 "any" 로 렌더되는데 렌더 자체는 성공해서
     * 조용히 쓸모없는 문서가 나간다.
     */
    private String typeOf(JsonNode schema, JsonNode oas) {
        String ref = text(schema.path("$ref"), null);
        if (ref != null) {
            return ref.substring(ref.lastIndexOf('/') + 1);
        }

        String type = text(schema.path("type"), null);
        if (type == null) {
            JsonNode types = schema.path("types");
            if (types.isArray() && !types.isEmpty()) {
                type = types.get(0).asText();
            }
        }
        if (type == null) {
            if (!schema.path("properties").isMissingNode()) {
                return "객체";
            }
            return "any";
        }

        String format = text(schema.path("format"), null);
        String korean = switch (type) {
            case "string" -> "문자열";
            case "integer" -> "정수";
            case "number" -> "실수";
            case "boolean" -> "불리언";
            case "array" -> "배열";
            case "object" -> "객체";
            default -> type;
        };
        return format != null ? korean + "(" + format + ")" : korean;
    }

    private String enumSuffix(JsonNode schema) {
        JsonNode values = schema.path("enum");
        if (!values.isArray() || values.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode value : values) {
            names.add(value.asText());
        }
        return " — " + String.join(" | ", names);
    }

    private static String text(JsonNode node, String fallback) {
        return node.isMissingNode() || node.isNull() || node.asText().isBlank()
                ? fallback
                : node.asText();
    }

    private record Operation(String method, String path, JsonNode node) {
    }
}
