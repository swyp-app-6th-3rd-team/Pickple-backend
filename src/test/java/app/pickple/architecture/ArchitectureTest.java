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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
         * ⚠️ 아직 켜지 않았다 — <b>규칙이 틀려서가 아니라 코드가 아직 안 따라와서다.</b>
         *
         * <p>지금 이 규칙을 켜면 {@code AuthController}·{@code UserProfileController}·
         * {@code CommentController} 3개가 위반으로 잡힌다(실측). 이들을 고치는 것은
         * 곧 {@code /api} prefix 를 걷어내는 일이고, 그건 <b>프론트 합의가 끝나야</b>
         * 착수할 수 있는 API 계약 변경이다(ADR-0029 의 "열린 질문").
         *
         * <p>그럼에도 규칙을 지금 넣어두는 이유는, 컨트롤러 일괄 변경 PR 이
         * {@code @Disabled} 한 줄만 지우면 되도록 <b>안전망을 미리 깔아두기 위해서다.</b>
         * 규칙을 나중에 쓰면 그 사이에 들어온 컨트롤러가 옛 관행을 다시 심는다.
         *
         * <p><b>해제 조건</b>: 컨트롤러 5개의 클래스 레벨 매핑 제거 + SecurityConfig
         * 매처 동기화가 끝나면 이 애노테이션을 지운다.
         *
         * <p>규칙이 실제로 무언가를 잡는다는 증거는 있다 — 이 애노테이션을 떼면
         * 위 3개 클래스를 정확히 지목하며 실패한다(PR 본문에 기록).
         */
        @Test
        @Disabled("컨트롤러 일괄 변경(=/api prefix 제거, 프론트 합의 후) 시 이 줄을 지운다 — ADR-0029")
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

        /**
         * 매핑 애노테이션에서 경로를 읽는다.
         *
         * <p>⚠️ {@code value} 만 보면 안 된다. Spring 은 {@code value} 와 {@code path} 를
         * {@code @AliasFor} 로 묶지만 그 해석은 Spring 의 애노테이션 엔진이 하는 일이고,
         * ArchUnit 은 바이트코드를 그대로 읽으므로 <b>작성자가 쓴 속성만</b> 보인다.
         * 실제로 ImageUploadController 는 {@code path = "/api/images"} 로 쓴다 —
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
