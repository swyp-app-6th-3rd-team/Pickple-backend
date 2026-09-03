package app.pickple.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Window;
import org.springframework.data.repository.Repository;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
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

    @BeforeAll
    static void importClasses() {
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
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
            noClasses().that().resideInAPackage(BASE + "..")
                    .should().resideInAnyPackage(BASE + ".*.config..")
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
