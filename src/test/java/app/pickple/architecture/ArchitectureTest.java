package app.pickple.architecture;

import app.pickple.support.IntegrationTest;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Window;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 아키텍처 규칙. 이 템플릿의 구조는 이 테스트가 지킨다.
 *
 * <p>규칙을 추가할 때는 <b>일부러 위반하는 코드를 넣어 그 규칙만 실패하는지 확인한 뒤</b>
 * 커밋한다. 통과만으로는 규칙이 무언가를 지킨다는 증거가 되지 않는다 —
 * 테스트가 초록색인데 보호는 없는 상태가 가장 나쁘다.
 */
class ArchitectureTest {

    private static final String BASE = "app.pickple";

    /**
     * 모든 도메인 패키지. 특정 도메인({@code ..auth.domain..})으로 좁히면
     * 새 도메인이 생겼을 때 규칙이 그 도메인을 보지 못한다 —
     * 테스트는 초록색인데 보호는 없는 상태가 된다.
     */
    private static final String DOMAIN = "..domain..";

    private static JavaClasses classesUnderTest;
    private static JavaClasses classesIncludingTests;

    @BeforeAll
    static void importClasses() {
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
        classesIncludingTests = new ClassFileImporter()
                .importPackages(BASE);
    }


    // ---- 컨트롤러 매핑 공용 헬퍼 (ControllerMapping·ApiDocumentation 이 함께 쓴다) ----


    /**
     * Spring 의 매핑 애노테이션 5종({@code @GetMapping}·{@code @PostMapping} …)은
     * 전부 {@code @RequestMapping} 이 메타 애노테이션으로 붙어 있다. 그래서 종류를
     * 하나씩 나열하지 않고 메타 애노테이션 하나로 잡는다 — 나중에 합성 애노테이션이
     * 생겨도 규칙이 그것을 본다.
     */
    private static final DescribedPredicate<JavaMethod> HANDLER_METHODS =
            describe("핸들러 메서드", method ->
                    method.isMetaAnnotatedWith(RequestMapping.class)
                            || method.isAnnotatedWith(RequestMapping.class));


    /**
     * 매핑 애노테이션에서 경로를 읽는다.
     *
     * <p>⚠️ {@code value} 만 보면 안 된다. Spring 은 {@code value} 와 {@code path} 를
     * {@code @AliasFor} 로 묶지만 그 해석은 Spring 의 애노테이션 엔진이 하는 일이고,
     * ArchUnit 은 바이트코드를 그대로 읽으므로 <b>작성자가 쓴 속성만</b> 보인다.
     * 실제로 ImageUploadController 는 {@code path = "/images"} 로 쓴다 —
     * {@code value} 만 검사하면 멀쩡한 코드가 위반으로 잡힌다.
     */
    private static Optional<String> mappingPathOf(JavaMethod method) {
        for (JavaAnnotation<JavaMethod> annotation : method.getAnnotations()) {
            if (!annotation.getRawType().isMetaAnnotatedWith(RequestMapping.class)
                    && !annotation.getRawType().isAssignableTo(RequestMapping.class)) {
                continue;
            }
            Optional<String> path = firstNonBlank(annotation, "value")
                    .or(() -> firstNonBlank(annotation, "path"));
            if (path.isPresent()) {
                return path;
            }
        }
        return Optional.empty();
    }


    private static Optional<String> firstNonBlank(JavaAnnotation<JavaMethod> annotation, String property) {
        Object raw = annotation.get(property).orElse(null);
        if (!(raw instanceof Object[] values)) {
            return Optional.empty();
        }
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    @Nested
    @DisplayName("테스트 네이밍")
    class TestNaming {

        @Test
        @DisplayName("통합 테스트 클래스 이름은 IT 로 끝난다")
        void integrationTestsEndWithIT() {
            classes().that().areAnnotatedWith(IntegrationTest.class)
                    .should().haveSimpleNameEndingWith("IT")
                    .check(classesIncludingTests);
        }
    }

    @Nested
    @DisplayName("도메인 순수성")
    class DomainPurity {

        @Test
        @DisplayName("도메인은 JPA 에 의존하지 않는다")
        void noJpaDependency() {
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("도메인은 검증 애노테이션에 의존하지 않는다")
        void noValidationDependency() {
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAnyPackage("jakarta.validation..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("도메인은 롬복을 쓰지 않는다")
        void noLombokInDomain() {
            // @Builder 는 생성자 검증을 건너뛰고, @NoArgsConstructor 는 불변식을 우회하는
            // 기본 생성자를 열고, @Setter 는 상태 전이 규칙을 무력화한다.
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAnyPackage("lombok..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("도메인은 인프라를 알지 못한다")
        void domainDoesNotKnowInfra() {
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAnyPackage("..infra..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("도메인은 웹 계층을 알지 못한다")
        void domainDoesNotKnowWeb() {
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().resideInAnyPackage("..controller..", "org.springframework.web..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("도메인은 금액·수량에 실수형을 쓰지 않는다")
        void noFloatingPointInDomain() {
            // 부동소수점은 반올림 오차가 누적되어 합계를 어긋나게 만든다.
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Double")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Float")
                    .check(classesUnderTest);
        }
    }

    @Nested
    @DisplayName("계층 경계")
    class LayerBoundary {

        @Test
        @DisplayName("저장소 인터페이스는 도메인 패키지에만 존재한다")
        void storeInterfacesLiveInDomain() {
            classes().that().haveSimpleNameEndingWith("Store").and().areInterfaces()
                    .should().resideInAnyPackage("..domain..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("저장소 구현은 인프라 패키지에만 존재한다")
        void storeImplementationsLiveInInfra() {
            classes().that().haveSimpleNameEndingWith("Store").and().areNotInterfaces()
                    .should().resideInAnyPackage("..infra..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("엔티티는 인프라 패키지에만 존재한다")
        void entitiesLiveInInfra() {
            classes().that().haveSimpleNameEndingWith("Entity")
                    .should().resideInAnyPackage("..infra..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("서비스는 리포지토리에 직접 의존하지 않는다")
        void servicesDoNotUseRepositoriesDirectly() {
            // 서비스는 도메인의 Store 인터페이스만 본다.
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().areAssignableTo(Repository.class)
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("컨트롤러는 리포지토리에 직접 의존하지 않는다")
        void controllersDoNotUseRepositoriesDirectly() {
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().areAssignableTo(Repository.class)
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("컨트롤러는 엔티티를 노출하지 않는다")
        void controllersDoNotExposeEntities() {
            // 패키지 조건이 반드시 있어야 한다. 이름만으로 거르면 Spring 의
            // ResponseEntity 까지 걸린다(JPA 엔티티가 아닌데도).
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat(
                            resideInAPackage(BASE + "..").and(simpleNameEndingWith("Entity")))
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("서비스는 저장소 구현이 아니라 인터페이스에 의존한다")
        void servicesDependOnStoreInterfaces() {
            noClasses().that().resideInAPackage("..service..")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("Jpa")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("설정 클래스는 도메인별 config 하위 패키지에 두지 않는다")
        void configPackagesAreNotNestedUnderDomains() {
            // 루트 app.pickple.config 하나로 모은다(#63). 도메인마다 config 를 파면
            // 부트스트랩 설정이 흩어져 "이 앱의 설정 전체"를 한눈에 볼 수 없다.
            //
            // ⚠️ "모든 @Configuration 은 config 에 있어야 한다"로 쓰지 않는 이유:
            // AppleTokenClientConfiguration·DocsConfig 는 각자 기능 패키지에 두는 것이
            // 응집도상 맞다. 금지 대상은 클래스 위치가 아니라 **중첩된 config 패키지**다.
            //
            // ⚠️ 패턴 주의: `BASE + ".*.config.."` 는 `*` 가 한 세그먼트만 먹어서
            // app.pickple.auth.apple.config 같은 2단계 깊이를 놓친다(실제로 확인했다).
            // `..config..` 로 써야 깊이와 무관하게 잡힌다. 루트 app.pickple.config 는
            // 허용해야 하므로 그것만 예외로 뺀다.
            noClasses().that().resideInAPackage(BASE + "..")
                    .and().resideOutsideOfPackage(BASE + ".config")
                    .should().resideInAnyPackage(BASE + "..config..")
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("인프라는 서비스를 알지 못한다")
        void infraDoesNotKnowService() {
            // 이 방향이 열리면 service -> infra 와 함께 순환이 생긴다.
            noClasses().that().resideInAPackage("..infra..")
                    .should().dependOnClassesThat().resideInAPackage("..service..")
                    .check(classesUnderTest);
        }
    }

    @Nested
    @DisplayName("API 응답 계약")
    class ApiContract {

        @Test
        @DisplayName("컨트롤러는 Page 를 그대로 응답하지 않는다")
        void controllersDoNotReturnSpringPage() {
            // Page 를 직렬화하면 pageable.sort.sorted 같은 Spring 내부 구조가
            // API 계약이 되어버린다. PageResponse 로 변환해 내보낸다.
            noMethods().that().arePublic().and().areDeclaredInClassesThat().resideInAPackage("..controller..")
                    .should().haveRawReturnType(Page.class)
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("컨트롤러는 Window 를 그대로 응답하지 않는다")
        void controllersDoNotReturnSpringWindow() {
            noMethods().that().arePublic().and().areDeclaredInClassesThat().resideInAPackage("..controller..")
                    .should().haveRawReturnType(Window.class)
                    .check(classesUnderTest);
        }
    }

    @Nested
    @DisplayName("컨트롤러 매핑")
    class ControllerMapping {


        /**
         * 경로를 알려면 클래스와 메서드 애노테이션을 머릿속에서 합성해야 한다(ADR-0029).
         * 핸들러 메서드 한 줄만 보고 최종 경로를 알 수 있게 한다.
         *
         * <p><b>이 규칙은 옛 관행이 다시 심기는 것을 막는다.</b> {@code /api} prefix 를
         * 걷어내며(#91) 클래스 레벨 매핑을 전부 제거했는데, 규칙이 없으면 다음에 들어오는
         * 컨트롤러가 같은 형태를 되살린다.
         */
        @Test
        @DisplayName("@RestController 는 클래스 레벨 @RequestMapping 을 갖지 않는다")
        void restControllersHaveNoClassLevelRequestMapping() {
            // 경로를 알려면 클래스와 메서드 애노테이션을 머릿속에서 합성해야 한다(ADR-0029).
            // 핸들러 메서드 한 줄만 보고 최종 경로를 알 수 있게 한다.
            //
            // ⚠️ 여기서 metaAnnotatedWith 를 쓰지 않는다. @GetMapping 류가 전부
            // @RequestMapping 을 메타로 갖고 있어서, 메타까지 보면 메서드가 아니라
            // 클래스에 붙은 것만 봐야 하는 이 규칙의 의도가 무너진다.
            noClasses().that().areAnnotatedWith(RestController.class)
                    .should().beAnnotatedWith(RequestMapping.class)
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("핸들러 메서드의 매핑 경로는 비어 있지 않다")
        void handlerMethodsDeclareNonEmptyPath() {
            // 위 규칙의 짝인데, 이쪽은 지금 켠다. 경로를 명시하는 것은 URL 을 바꾸지 않아
            // 프론트 합의를 기다릴 이유가 없기 때문이다(구조만 바뀐다).
            //
            // 켜두는 값어치가 큰 쪽도 이쪽이다. 클래스 레벨 매핑이 사라진 컨트롤러에
            // 경로 없는 매핑(bare @GetMapping)이 들어오면 루트("")로 떨어지는데,
            // 컴파일도 통과하고 git 머지도 **충돌 없이** 끝나서 아무도 보지 못한다.
            // 실제로 진행 중인 PR #76 이 PostController 에 bare @PostMapping 을 들고 온다 —
            // 그 머지가 조용히 깨지는 대신 이 테스트가 빨간불로 잡는다.
            methods().that(HANDLER_METHODS)
                    .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .should(declareANonEmptyMappingPath())
                    .check(classesUnderTest);
        }

        private static ArchCondition<JavaMethod> declareANonEmptyMappingPath() {
            return new ArchCondition<>("매핑 경로를 비우지 않고 선언한다") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    Optional<String> path = mappingPathOf(method);
                    boolean satisfied = path.isPresent() && !path.get().isBlank();
                    events.add(new SimpleConditionEvent(method, satisfied,
                            satisfied
                                    ? method.getFullName() + " 는 매핑 경로를 선언한다"
                                    : method.getFullName() + " 에 매핑 경로가 없다"
                                    + " — 클래스 레벨 매핑이 사라지면 루트로 떨어진다"));
                }
            };
        }


    }

    @Nested
    @DisplayName("API 문서")
    class ApiDocumentation {

        /**
         * 인증 없이 부를 수 있는 엔드포인트. {@code SecurityConfig} 의
         * {@code PUBLIC_GET} 과 {@code permitAll} 목록을 그대로 옮긴 것이다.
         *
         * <p><b>왜 SecurityConfig 에서 읽지 않는가</b> — {@code PUBLIC_GET} 은
         * {@code private static} 이고 나머지 permitAll 은 {@code authorizeHttpRequests}
         * 람다 안에 흩어져 있다. ArchUnit 은 바이트코드를 읽으므로 람다 안의 문자열을
         * 꺼낼 수 없다. 그래서 <b>여기에 두 번째 정본이 생긴다</b> — 공개 엔드포인트를
         * 늘리거나 줄이면 {@code SecurityConfig} 와 이 목록을 함께 고쳐야 한다.
         * 함께 고치지 않으면 이 테스트가 빨간불로 알려준다(그게 이 목록의 값어치다).
         *
         * <p>키는 {@code "METHOD 경로"} 다. 같은 경로라도 메서드마다 공개 여부가
         * 갈리기 때문이다 — {@code GET /posts/{postId}/comments} 는 공개지만
         * {@code POST} 는 인증이 필요하다.
         */
        private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
                "GET /posts",
                "GET /posts/popular",
                "GET /posts/{postId}/comments",
                "GET /users/nickname/availability",
                "GET /rankings",
                "GET /rankings/top",
                "POST /auth/apple",
                "POST /auth/kakao",
                "POST /auth/refresh",
                "POST /auth/mobile/refresh",
                "POST /auth/logout");

        /**
         * 인증이 필요한 핸들러에는 {@code @SecurityRequirement} 가 붙어야 한다.
         *
         * <p><b>왜 필요한가</b> — springdoc 은 {@code SecurityConfig} 를 읽지 않는다.
         * 컨트롤러 애노테이션만 보고 스펙을 만든다. 그래서 이 애노테이션이 없으면
         * {@code .anyRequest().authenticated()} 로 실제로는 401 이 나는 API 가
         * 문서에는 자물쇠 없이 실린다 — FE 는 토큰이 필요한지 모른 채 호출한다.
         * 컴파일도 통과하고 테스트도 통과하므로 <b>이 규칙 말고는 잡을 것이 없다</b>
         * (ADR-0034).
         *
         * <p>공개 엔드포인트에는 <b>붙이지 않는다</b>. 값 없는
         * {@code @SecurityRequirements} 로 전역 잠금을 푸는 방식도 쓰지 않는다 —
         * 그러면 인증이 필요한 쪽에 표시가 없고 공개 쪽에만 애노테이션이 붙어
         * 코드가 정반대로 읽힌다.
         */
        /**
         * 문서 자신을 내보내는 컨트롤러. 스펙에 실리지 않으므로 이 규칙의 대상이 아니다.
         *
         * <p>{@code springdoc.paths-to-exclude} 가 {@code /scalar/**}·{@code /llms.txt}·
         * {@code /llms.md} 를 스펙에서 뺀다. 스펙에 없는 경로에는 자물쇠를 붙일 자리도 없다.
         *
         * <p>경로가 아니라 <b>선언 클래스</b>로 거른다. {@code ScalarConfig} 의 매핑은
         * {@code @GetMapping("${scalar.path:/scalar}")} 라 바이트코드에 플레이스홀더가
         * 그대로 남는다 — ArchUnit 은 Spring 의 프로퍼티 해석을 거치지 않으므로
         * 경로 문자열로는 이것을 알아볼 수 없다.
         */
        private static final Set<String> DOCS_CONTROLLERS = Set.of(
                "app.pickple.config.ScalarConfig",
                "app.pickple.docs.LlmsTxtController");

        @Test
        @DisplayName("permitAll 이 아닌 핸들러는 @SecurityRequirement 를 갖는다")
        void authenticatedHandlersDeclareSecurityRequirement() {
            methods().that(HANDLER_METHODS)
                    .and().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                    .and().areDeclaredInClassesThat(
                            describe("문서 컨트롤러가 아닌", javaClass ->
                                    !DOCS_CONTROLLERS.contains(javaClass.getFullName())))
                    .should(declareSecurityRequirementUnlessPublic())
                    .check(classesUnderTest);
        }

        private static ArchCondition<JavaMethod> declareSecurityRequirementUnlessPublic() {
            return new ArchCondition<>("인증이 필요하면 @SecurityRequirement 를 선언한다") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    String endpoint = endpointOf(method);
                    boolean isPublic = PUBLIC_ENDPOINTS.contains(endpoint);
                    boolean annotated = method.isAnnotatedWith(SecurityRequirement.class)
                            || method.isAnnotatedWith(SecurityRequirements.class);

                    boolean satisfied = isPublic != annotated;

                    String message;
                    if (satisfied) {
                        message = method.getFullName() + " (" + endpoint + ") 는 인증 표시가 맞다";
                    } else if (isPublic) {
                        message = method.getFullName() + " (" + endpoint + ") 는 공개 엔드포인트인데"
                                + " 인증 애노테이션이 붙어 있다 — 자물쇠가 잘못 뜬다";
                    } else {
                        message = method.getFullName() + " (" + endpoint + ") 에"
                                + " @SecurityRequirement(name = \"bearerAuth\") 가 없다"
                                + " — 401 이 나는 API 가 문서에는 공개로 실린다";
                    }
                    events.add(new SimpleConditionEvent(method, satisfied, message));
                }
            };
        }

        /** {@code "GET /posts"} 형태로 만든다. {@link #PUBLIC_ENDPOINTS} 의 키와 같은 모양이다. */
        private static String endpointOf(JavaMethod method) {
            return httpMethodOf(method) + " " + mappingPathOf(method).orElse("");
        }

        /**
         * 매핑 애노테이션에서 HTTP 메서드를 읽는다.
         *
         * <p>{@code @GetMapping} 류는 애노테이션 이름이 곧 메서드다. {@code @RequestMapping}
         * 을 직접 쓰면 {@code method} 속성을 봐야 하는데, 이 저장소는 핸들러에 그것을
         * 쓰지 않으므로 이름에서 뽑는다. 쓰기 시작하면 이 메서드가 {@code ""} 를 돌려주고
         * 그 핸들러는 {@code PUBLIC_ENDPOINTS} 에 걸리지 않아 애노테이션을 요구받는다 —
         * 안전한 방향으로 틀린다.
         */
        private static String httpMethodOf(JavaMethod method) {
            for (JavaAnnotation<JavaMethod> annotation : method.getAnnotations()) {
                String name = annotation.getRawType().getSimpleName();
                if (name.endsWith("Mapping") && !name.equals("RequestMapping")) {
                    return name.replace("Mapping", "").toUpperCase();
                }
            }
            return "";
        }
    }

    /**
     * 탈퇴 회원 차단 관문이 배선에서 빠지지 않게 한다 (#106, ADR-0035).
     *
     * <p><b>ArchUnit 은 이 결함을 못 잡았다.</b> 그게 뚫린 이유다 — 기존 규칙은 계층 경계와
     * 명명만 보았고, "탈퇴자가 쓰기 경로를 지날 수 있다" 는 그 어느 것도 위반하지 않았다.
     * 그래서 새 규칙의 조준점은 <b>관문이 배선에 남아 있는가</b> 하나다.
     *
     * <p><b>이 규칙이 잡지 못하는 것을 분명히 해 둔다.</b> ArchUnit 은 바이트코드를 읽으므로
     * {@code SecurityConfig} 람다 안의 {@code .anyRequest().access(...)} 가
     * {@code .authenticated()} 로 바뀌었는지는 <b>보지 못한다</b>. 여기서 보는 것은
     * "의존 관계가 남아 있는가" 까지다. 실제 차단 동작은
     * {@code WithdrawnUserAuthorizationIT} 가 HTTP 로 확인한다 — 두 개가 함께 있어야 보호가 된다.
     */
    @Nested
    @DisplayName("탈퇴 회원 차단 관문 (#106)")
    class WithdrawnUserGate {

        private static final String GATE =
                "app.pickple.auth.security.ActiveAccountAuthorizationManager";
        private static final String DEMOTION =
                "app.pickple.auth.security.AnonymousDemotionFilter";
        private static final String UNAVAILABLE =
                "app.pickple.auth.security.AccountStateUnavailableFilter";

        @Test
        @DisplayName("SecurityConfig 는 관문과 두 필터를 모두 배선한다")
        void securityConfigWiresGateAndFilters() {
            // 셋 중 하나만 빠져도 보호에 구멍이 난다:
            //   관문 없음        → 새 보호 엔드포인트가 자동 차단되지 않는다
            //   강등 필터 없음   → 공개 경로에서 탈퇴자가 본인으로 남고, 보호 경로는 403 이 된다
            //   503 필터 없음    → DB 장애가 컨테이너 기본 500(HTML)으로 새어 나간다
            classes().that().haveFullyQualifiedName("app.pickple.config.SecurityConfig")
                    .should().dependOnClassesThat().haveFullyQualifiedName(GATE)
                    .andShould().dependOnClassesThat().haveFullyQualifiedName(DEMOTION)
                    .andShould().dependOnClassesThat().haveFullyQualifiedName(UNAVAILABLE)
                    .check(classesUnderTest);
        }

        @Test
        @DisplayName("상태 조회는 저장소 인터페이스를 지난다 — 필터가 리포지토리를 직접 부르지 않는다")
        void stateLookupGoesThroughTheStore() {
            // 관문·필터가 UserRepository 를 직접 잡으면 infra 가 auth.security 로 새고,
            // 조회 모양(좁은 존재 확인)을 한 곳에서 지킬 수 없게 된다.
            noClasses().that().resideInAPackage("..auth.security..")
                    .should().dependOnClassesThat().resideInAPackage("..infra..")
                    .check(classesUnderTest);
        }
    }

    @Nested
    @DisplayName("의존성 주입")
    class DependencyInjection {

        @Test
        @DisplayName("필드 주입을 쓰지 않는다")
        void noFieldInjection() {
            // 생성자 주입이라야 final 을 쓸 수 있고 테스트에서 교체가 쉽다.
            fields().should().notBeAnnotatedWith(Autowired.class)
                    .check(classesUnderTest);
        }
    }
}
