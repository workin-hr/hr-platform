# F-23 Authorization-Policy Declarations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the authorization-policy declaration annotations, typed permission constants, and the ArchUnit rules that fail the build on any undeclared externally reachable use case, per `docs/superpowers/specs/2026-08-06-f23-authorization-policy-declarations-design.md`.

**Architecture:** Three method annotations in a new `authorization` package; a `PermissionKeys` constants class kept in exact sync with V4's `permissions` catalog by an integration test; one plain-ArchUnit test class holding three rules plus fixture-based proven-to-fail tests. No behavior changes.

**Tech Stack:** Java 25, ArchUnit 1.4.2 (already a dependency), JUnit 5, Testcontainers (sync test only). Tests run via WSL as established.

## Global Constraints

- Exactly one policy annotation per handler method; `@RequiresPermission` frozen (Rule 3) until runtime enforcement lands.
- Production rules import with `ImportOption.Predefined.DO_NOT_INCLUDE_TESTS`; fixtures live only in test sources.
- No Spring-behavior or dependency changes; annotations are plain Java.
- Tabs in Java sources (house style); this plan's code blocks use 4-space indentation only to satisfy markdownlint (MD010) — convert to tabs when writing the actual files.

---

### Task 1: ArchUnit rules first (red on the real, unannotated surface), then annotations + existing-surface annotation

**Files:**

- Test: `backend/src/test/java/com/workin/backend/authorization/AuthorizationPolicyArchTest.java`
- Test fixtures: `backend/src/test/java/com/workin/backend/authorization/archfixtures/` (`UndeclaredHandlerFixture.java`, `DoublyDeclaredHandlerFixture.java`, `RequiresPermissionUsageFixture.java`, `PolicyOnNonHandlerFixture.java`)
- Create: `backend/src/main/java/com/workin/backend/authorization/PublicUseCase.java`, `AuthenticatedUseCase.java`, `RequiresPermission.java`
- Modify: `AuthController.java`, `PlatformAdminAuthController.java`, `TenantController.java`, `PlatformAdminController.java` (add one annotation per handler)

**Interfaces:**

- Produces: `@PublicUseCase(reason)`, `@AuthenticatedUseCase(reason)` (both `String reason()`, no default), `@RequiresPermission(String value())` — all `@Target(ElementType.METHOD)`, `@Retention(RetentionPolicy.RUNTIME)`, `@Documented`.
- Task 2 consumes nothing from Task 1; Task 3 consumes green state.

- [ ] **Step 1: Write the arch test** — rules as named constants so fixture tests reuse the exact production rules:

```java
package com.workin.backend.authorization;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * F-23 / ADR-0010 Dimension 4 (Required Implementation Task 10): the
 * build fails when an externally reachable use case has no
 * authorization-policy declaration. Fixture-based tests below prove
 * each rule actually fails on its violation shape -- the matrix's
 * "proven to fail on an undeclared use case" closure criterion, kept
 * alive in the suite rather than checked once by hand.
 */
class AuthorizationPolicyArchTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.workin.backend");

    private static final DescribedPredicate<JavaMethod> HANDLER_METHOD =
            new DescribedPredicate<>("a controller handler method (meta-annotated with @RequestMapping)") {
                @Override
                public boolean test(JavaMethod method) {
                    return method.isMetaAnnotatedWith(RequestMapping.class);
                }
            };

    private static final ArchCondition<JavaMethod> HAVE_EXACTLY_ONE_POLICY_DECLARATION =
            new ArchCondition<>("declare exactly one authorization policy "
                    + "(@PublicUseCase, @AuthenticatedUseCase, or @RequiresPermission)") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    int declarations = 0;
                    if (method.isAnnotatedWith(PublicUseCase.class)) declarations++;
                    if (method.isAnnotatedWith(AuthenticatedUseCase.class)) declarations++;
                    if (method.isAnnotatedWith(RequiresPermission.class)) declarations++;
                    if (declarations != 1) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " declares " + declarations + " authorization policies (must be exactly 1)"));
                    }
                }
            };

    static final ArchRule EVERY_HANDLER_DECLARES_A_POLICY = methods()
            .that(HANDLER_METHOD)
            .should(HAVE_EXACTLY_ONE_POLICY_DECLARATION)
            .because("ADR-0010 Dimension 4: every externally reachable use case must declare its "
                    + "authorization policy or an explicit public/authentication-only marker (F-23)");

    static final ArchRule POLICY_ANNOTATIONS_ONLY_ON_HANDLERS = methods()
            .that().areAnnotatedWith(PublicUseCase.class)
            .or().areAnnotatedWith(AuthenticatedUseCase.class)
            .or().areAnnotatedWith(RequiresPermission.class)
            .should(new ArchCondition<>("be controller handler methods") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    if (!method.isMetaAnnotatedWith(RequestMapping.class)) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " carries a policy annotation but is not a handler method -- nothing enforces it there"));
                    }
                }
            })
            .because("a policy annotation on a non-handler method is decorative and misleading; the "
                    + "declaration point moves to the application-service layer only when that layer exists");

    static final ArchRule REQUIRES_PERMISSION_IS_FROZEN = noMethods()
            .should().beAnnotatedWith(RequiresPermission.class)
            .because("@RequiresPermission has no runtime enforcement yet -- a permission-gated endpoint "
                    + "would be decorative security. Delete this rule in the same PR that lands the "
                    + "runtime permission-evaluation component (F-15/F-17) and wires the annotation to it");

    @Test
    void everyExternallyReachableHandlerDeclaresExactlyOnePolicy() {
        EVERY_HANDLER_DECLARES_A_POLICY.check(PRODUCTION_CLASSES);
    }

    @Test
    void policyAnnotationsAppearOnlyOnHandlerMethods() {
        POLICY_ANNOTATIONS_ONLY_ON_HANDLERS.check(PRODUCTION_CLASSES);
    }

    @Test
    void requiresPermissionIsFrozenUntilRuntimeEnforcementExists() {
        REQUIRES_PERMISSION_IS_FROZEN.check(PRODUCTION_CLASSES);
    }

    // ---- proven-to-fail evidence (fixtures imported explicitly) ----

    private static JavaClasses fixture(Class<?> fixtureClass) {
        return new ClassFileImporter().importClasses(fixtureClass,
                PublicUseCase.class, AuthenticatedUseCase.class, RequiresPermission.class);
    }

    private static boolean hasViolations(ArchRule rule, Class<?> fixtureClass) {
        EvaluationResult result = rule.evaluate(fixture(fixtureClass));
        return result.hasViolation();
    }

    @Test
    void anUndeclaredHandlerIsAViolation() {
        assertThat(hasViolations(EVERY_HANDLER_DECLARES_A_POLICY,
                com.workin.backend.authorization.archfixtures.UndeclaredHandlerFixture.class)).isTrue();
    }

    @Test
    void aDoublyDeclaredHandlerIsAViolation() {
        assertThat(hasViolations(EVERY_HANDLER_DECLARES_A_POLICY,
                com.workin.backend.authorization.archfixtures.DoublyDeclaredHandlerFixture.class)).isTrue();
    }

    @Test
    void aRequiresPermissionUsageIsAViolationWhileFrozen() {
        assertThat(hasViolations(REQUIRES_PERMISSION_IS_FROZEN,
                com.workin.backend.authorization.archfixtures.RequiresPermissionUsageFixture.class)).isTrue();
    }

    @Test
    void aPolicyAnnotationOnANonHandlerMethodIsAViolation() {
        assertThat(hasViolations(POLICY_ANNOTATIONS_ONLY_ON_HANDLERS,
                com.workin.backend.authorization.archfixtures.PolicyOnNonHandlerFixture.class)).isTrue();
    }

}
```

Fixtures (test sources, package `com.workin.backend.authorization.archfixtures`; each a tiny `@RestController` — e.g. `UndeclaredHandlerFixture` has one `@GetMapping("/archfixture/undeclared") String get()` with no policy annotation; `DoublyDeclaredHandlerFixture`'s handler carries both `@PublicUseCase(reason = "fixture")` and `@AuthenticatedUseCase(reason = "fixture")`; `RequiresPermissionUsageFixture`'s handler carries `@RequiresPermission("employees.read")`; `PolicyOnNonHandlerFixture` has a plain non-mapped method carrying `@PublicUseCase(reason = "fixture")`). These are never component-scanned in tests that boot Spring? They ARE in the test classpath — add `@RestController` only where the rule needs it for realism but NO `@RequestMapping` path collisions with real routes; Spring test contexts in this suite component-scan only `com.workin.backend` via `@SpringBootApplication` — test classes are not scanned, so no runtime effect.

- [ ] **Step 2: Run — expect red** (annotations don't exist → compile failure):
`./gradlew test --tests 'com.workin.backend.authorization.*'`

- [ ] **Step 3: Create the three annotations**, e.g.:

```java
package com.workin.backend.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this externally reachable use case is intentionally
 * public -- reachable without any authentication (ADR-0010 Dimension
 * 4's explicit marker; F-23). The mandatory reason makes the intent
 * reviewable at the declaration site.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicUseCase {

    String reason();

}
```

(`AuthenticatedUseCase` identical with its own Javadoc; `RequiresPermission` has `String value()` and Javadoc stating it is frozen by the arch rule until F-15/F-17 land runtime enforcement.)

- [ ] **Step 4: Run again — expect red on the real surface** (8 existing handlers undeclared). This is the live proof the rule bites.

- [ ] **Step 5: Annotate the existing surface** (one line per handler):

| Handler | Annotation |
|---|---|
| `AuthController.register` | `@PublicUseCase(reason = "company self-registration is the entry point that creates the first credential")` |
| `AuthController.login` | `@PublicUseCase(reason = "credential presentation -- authentication happens inside, not before")` |
| `AuthController.refresh` | `@PublicUseCase(reason = "refresh-token possession is the credential; the access token may already be expired")` |
| `AuthController.logout` | `@PublicUseCase(reason = "idempotent revocation by refresh-token possession; never an oracle")` |
| `PlatformAdminAuthController.login` | `@PublicUseCase(reason = "credential presentation for the platform domain")` |
| `PlatformAdminAuthController.refresh` | `@PublicUseCase(reason = "refresh-token possession is the credential; the access token may already be expired")` |
| `PlatformAdminAuthController.logout` | `@PublicUseCase(reason = "idempotent revocation by refresh-token possession; never an oracle")` |
| `TenantController.me` | `@AuthenticatedUseCase(reason = "builds and returns the caller's own validated membership context; no catalog permission of its own")` |
| `PlatformAdminController.me` | `@AuthenticatedUseCase(reason = "returns the authenticated admin's own record; no platform.* capability involved")` |

- [ ] **Step 6: Run — expect green.** Commit: `feat(backend): F-23 authorization-policy declarations + ArchUnit enforcement`

---

### Task 2: PermissionKeys constants + catalog sync test

**Files:**

- Test: `backend/src/test/java/com/workin/backend/authorization/PermissionCatalogSyncTest.java`
- Create: `backend/src/main/java/com/workin/backend/authorization/PermissionKeys.java`

- [ ] **Step 1: Write the sync test** (integration; reflection over `PermissionKeys` public static final String fields vs `SELECT permission_key FROM permissions`, assert set equality both directions with named differences).
- [ ] **Step 2: Run — red** (class missing).
- [ ] **Step 3: Write `PermissionKeys`** — final class, private constructor, one constant per V4 seed row (39 keys, `REPORTS_READ = "reports.read"` … `PLATFORM_COMPANIES_DELETE = "platform.companies.delete"`), Javadoc: single source of truth is the `permissions` table; this class mirrors it for compile-time-safe references, enforced by `PermissionCatalogSyncTest`.
- [ ] **Step 4: Run — green.** Commit.

---

### Task 3: Full suite, matrix update, lint

- [ ] **Step 1:** `./gradlew test --rerun` full suite green (expect 51 + new tests).
- [ ] **Step 2:** Matrix row F-23 → implemented (rules + proven-to-fail fixtures + frozen `@RequiresPermission` with removal condition; service-layer rule extension tracked for the first business module). Note on F-15/F-17 rows: unfreezing `@RequiresPermission` is part of their closure.
- [ ] **Step 3:** `markdownlint` clean; commit docs.
