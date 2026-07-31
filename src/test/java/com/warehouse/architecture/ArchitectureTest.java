package com.warehouse.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "com.warehouse",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layeredArchitectureShouldBeRespected =
            layeredArchitecture()
                    .consideringOnlyDependenciesInLayers()
                    .layer("Controller").definedBy("..controller..")
                    .layer("Service").definedBy("..service..")
                    .layer("Repository").definedBy("..repository..")
                    .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                    .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
                    .because("controllers must use services, and repositories must be accessed only by services");

    @ArchTest
    static final ArchRule controllersShouldNotDependOnRepositories =
            noClasses()
                    .that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..repository..")
                    .because("controllers must access data only through services");

    @ArchTest
    static final ArchRule controllersShouldNotReturnEntities =
            methods()
                    .that().areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .should(notReturnEntityTypes())
                    .because("controllers must expose DTOs instead of JPA entities");

    @ArchTest
    static final ArchRule restControllersShouldHaveControllerSuffix =
            classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should().haveSimpleNameEndingWith("Controller")
                    .because("REST controllers should follow the *Controller naming convention");

    @ArchTest
    static final ArchRule servicesShouldHaveServiceSuffix =
            classes()
                    .that().resideInAPackage("..service..")
                    .and().areTopLevelClasses()
                    .and().areNotInterfaces()
                    .should().haveSimpleNameEndingWith("Service")
                    .orShould().haveSimpleNameEndingWith("ServiceImpl")
                    .because("service classes should follow the *Service or *ServiceImpl naming convention");

    @ArchTest
    static final ArchRule springDataRepositoriesShouldHaveRepositorySuffix =
            classes()
                    .that().areAssignableTo(Repository.class)
                    .should().haveSimpleNameEndingWith("Repository")
                    .because("Spring Data repositories should follow the *Repository naming convention");

    @ArchTest
    static final ArchRule specificationsShouldHaveSpecificationSuffix =
            classes()
                    .that().resideInAPackage("..specification..")
                    .should().haveSimpleNameEndingWith("Specification")
                    .because("specification classes should follow the *Specification naming convention");

    private static ArchCondition<JavaMethod> notReturnEntityTypes() {
        return new ArchCondition<>("not return types from the entity package") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean returnsEntity = method.getReturnType()
                        .getAllInvolvedRawTypes()
                        .stream()
                        .anyMatch(type ->
                                type.getPackageName().startsWith("com.warehouse.entity")
                                        && !type.isEnum()
                        );

                if (returnsEntity) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " returns an entity type"
                    ));
                }
            }
        };
    }
}
