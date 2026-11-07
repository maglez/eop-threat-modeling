package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

/**
 * Enforces ADR-052: every {@code @ConditionalOnProperty} in main source carries {@code havingValue = "true"}.
 *
 * <p>ADR-013 mandates the tight form, and the reason is a fail-open reduction in Spring's own condition
 * evaluator. When {@code havingValue} is left empty, {@code OnPropertyCondition$Spec.isMatch} in the pinned
 * {@code spring-boot-autoconfigure} 4.1.0 reduces to {@code !"false".equalsIgnoreCase(value)}. The loose form
 * therefore <strong>enables</strong> the gated feature for every present value except the literal
 * {@code false} — including {@code off}, {@code no}, {@code 0}, {@code disabled}, and the empty string that
 * {@code EOP_FEATURES_SOMETHING=} exported empty produces. {@code off} is the worst of them: it is both the
 * spelling an operator reaches for as a kill switch and a YAML 1.1 boolean false, so it reads "disabled" to a
 * human and "enabled" to Spring. Note that {@code matchIfMissing} already defaults to {@code false}, so an
 * <em>absent</em> property was never the hole <em>in the loose form</em> — that hole is confined to
 * present-but-not-{@code false}.
 *
 * <p>{@code matchIfMissing} is audited here too, as a second and independent invariant, because it reaches the
 * same fail-open shape through a different attribute. A site written {@code havingValue = "true",
 * matchIfMissing = true} satisfies the ADR-013 mandate to the letter while enabling the feature when the
 * property is <em>absent</em> — the exact inverse of the fail-closed default {@code feature-flags.md} mandates
 * ("an unset flag reads as disabled, so forgetting to think about one fails closed"). It is in one respect
 * worse than the loose {@code havingValue} it would sit beside, because it needs no operator mistake at all to
 * fire: shipping the annotation is sufficient. No site sets it today, so this half of the gate is preventive in
 * the same way the first half is. It was added during EOP-50's own review round, on the finding that a gate
 * checking only {@code havingValue} would have admitted a configuration defeating that gate's own purpose.
 *
 * <p>The reason that mandate needs a test rather than only a rule and an ADR is that prose was tried and
 * demonstrably did not hold. ADR-013 already required the tight form when it was violated <em>twice, in two
 * independent commits</em>: {@code TrickController} (fixed in {@code 56c31b1}, EOP-14 slice D) and
 * {@code SessionController} plus four unconditional use-case beans (fixed in {@code 34d30d7} and
 * {@code 50850d1}, EOP-48). Both were caught by a reviewer rather than by the build, which is the failure this
 * class removes. Every site complies as of EOP-50, so this gate is <em>preventive</em>: it exists to stop the
 * third recurrence, not to fix a present defect.
 *
 * <p>The assertions read <strong>compiled bytecode</strong> rather than Java source text, which buys two
 * properties that a {@code docs}-style textual guard could not have. Source formatting cannot defeat them: a
 * multi-line annotation, an unusual attribute order, an interleaved comment and a line wrap are all invisible
 * at this level. And annotation <em>defaults are applied by the reader</em>, so an omitted {@code havingValue}
 * arrives as the empty string and is rejected by a positive comparison against {@code "true"} — this guard
 * proves a value is correct rather than proving a piece of text is absent, which is the weaker formulation
 * that {@code build-quality.md} warns about for the prose gates. That is also why this class lives in
 * {@code config} and not in {@code docs}: it asserts a property of compiled code, not of prose.
 *
 * <p>Scope is deliberately <em>every</em> {@code @ConditionalOnProperty} under {@code org.maglez.eop}, not only
 * those naming an {@code eop.features.*} key. Infrastructure toggles default closed for the same fail-open
 * reason a feature flag does — {@code caching.md} notes it "matters more here than elsewhere" for a cache
 * switch sitting in front of security-sensitive reads — so a future non-flag conditional is correctly in
 * scope. If a genuine need for a non-{@code "true"} {@code havingValue} ever arises, amend ADR-052 and add a
 * narrowly justified allow-list under review. Do not weaken the comparison to accommodate one call site.
 *
 * <p>What this class deliberately does <strong>not</strong> check is the second half of the ADR-013 mandate:
 * that a flag be repeated on every bean which opens or mutates the flagged state, rather than on the
 * controller alone. That clause is not mechanically checkable and ADR-052 says so openly. "Opens or mutates
 * the flagged state" is a semantic judgement about domain behaviour that no annotation or type signature
 * encodes, the rule's own carve-outs for pure reads and for collaborators shared with a second flag are
 * justified in javadoc prose that is unparseable as intent, and mechanising it would require a hand-maintained
 * register of which beans belong to which flag — recreating precisely the drift-prone second register that
 * EOP-48's security analysis rejected. So the first clause is machine-enforced here and the second remains
 * reviewer-enforced, an asymmetry worth knowing before citing this test as proof the whole mandate is
 * automated.
 */
@DisplayName("ADR-052: every @ConditionalOnProperty carries havingValue = \"true\" and no matchIfMissing")
class ConditionalOnPropertyHavingValueTest {

    /** Fully-qualified name of the annotation under audit, referenced as text so no outward import is needed. */
    private static final String CONDITIONAL_ON_PROPERTY =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

    /**
     * The only {@code havingValue} this repository permits.
     *
     * <p>Compared case-sensitively and exactly. Spring itself would accept {@code "TRUE"}, but a mandate whose
     * spelling varies is one a reader cannot check at a glance, and the ADR-013 wording names this literal.
     */
    private static final String REQUIRED_HAVING_VALUE = "true";

    /**
     * Root of the compiled main classes.
     *
     * <p>Scanned as a directory rather than through a {@code classpath*:} pattern on purpose. A classpath scan
     * resolves against every root on the test classpath, which would also sweep in {@code target/test-classes}
     * and any dependency jar that happened to ship this package — so it would audit code that is not ours and
     * that ADR-052 does not govern. Walking the main output directory means exactly "the classes compiled from
     * {@code src/main/java}". Surefire's working directory is the project base directory, so the relative path
     * resolves; the {@code compile} phase precedes {@code test}, so the directory is populated by the time this
     * runs. Both assumptions are backstopped by the vacuity guards below rather than trusted silently.
     */
    private static final Path MAIN_CLASSES = Path.of("target", "classes");

    @Test
    @DisplayName("no annotation site omits or loosens havingValue")
    void shouldRequireHavingValueTrueAtEverySite() {
        final List<ConditionSite> offenders = sites().stream()
                .filter(site -> !REQUIRED_HAVING_VALUE.equals(site.havingValue()))
                .toList();

        assertThat(offenders)
                .as("An empty or non-\"true\" havingValue enables the feature for every present value but the "
                        + "literal false — the empty string and \"off\" included. See ADR-052. Offending sites")
                .isEmpty();
    }

    @Test
    @DisplayName("no annotation site turns the absent property into an enabled feature")
    void shouldForbidMatchIfMissingAtEverySite() {
        final List<ConditionSite> offenders = sites().stream()
                .filter(ConditionSite::matchIfMissing)
                .toList();

        assertThat(offenders)
                .as("matchIfMissing = true enables the feature when the property is absent, inverting the "
                        + "fail-closed default that an unset flag reads as disabled. It passes the havingValue "
                        + "assertion untouched, which is why it is checked separately. See ADR-052. "
                        + "Offending sites")
                .isEmpty();
    }

    @Test
    @DisplayName("class-level annotations were actually discovered, so the audit is not vacuous")
    void shouldDiscoverClassLevelSites() {
        assertThat(sites().stream().filter(ConditionSite::classLevel).toList())
                .as("If this is empty the scanner found nothing and the assertion above is lying. Check that %s "
                        + "exists and that the compile phase ran", MAIN_CLASSES.toAbsolutePath())
                .isNotEmpty();
    }

    @Test
    @DisplayName("method-level annotations were actually discovered, the placement easiest to miss")
    void shouldDiscoverMethodLevelSites() {
        assertThat(sites().stream().filter(site -> !site.classLevel()).toList())
                .as("Most gated beans in this repository are @Bean methods in ApplicationConfiguration, so a "
                        + "scanner blind to method-level annotations would miss the majority of sites while "
                        + "still passing the class-level guard. Found none")
                .isNotEmpty();
    }

    /** Reads every compiled main class and returns one entry per {@code @ConditionalOnProperty} found. */
    private List<ConditionSite> sites() {
        final MetadataReaderFactory factory = new SimpleMetadataReaderFactory();
        final List<ConditionSite> found = new ArrayList<>();
        for (final Path classFile : compiledClassFiles()) {
            final AnnotationMetadata metadata = readMetadata(factory, classFile);
            if (metadata.hasAnnotation(CONDITIONAL_ON_PROPERTY)) {
                found.add(siteOf(metadata.getClassName(), null,
                        metadata.getAnnotationAttributes(CONDITIONAL_ON_PROPERTY)));
            }
            for (final MethodMetadata method : metadata.getAnnotatedMethods(CONDITIONAL_ON_PROPERTY)) {
                found.add(siteOf(method.getDeclaringClassName(), method.getMethodName(),
                        method.getAnnotationAttributes(CONDITIONAL_ON_PROPERTY)));
            }
        }
        return found;
    }

    /**
     * Builds a {@link ConditionSite} from a raw attribute map.
     *
     * <p>Unions {@code name} and {@code value}, because the two are declared aliases on this annotation: a site
     * written {@code @ConditionalOnProperty("eop.features.x")} puts its key in {@code value} and leaves
     * {@code name} empty. Reading only {@code name} would report such a site as keyless and, worse, invite a
     * future reader to conclude the alias form is out of scope. {@code prefix} is joined back on so the failure
     * message names the effective property key an operator would actually set. {@code matchIfMissing} is read
     * through {@code Boolean.parseBoolean} over the string form rather than cast, so the site is described
     * identically whether the metadata reader hands back a {@code Boolean} or its textual rendering.
     *
     * @param className declaring class, always present
     * @param methodName annotated method, or {@code null} for a class-level annotation
     * @param attributes attribute map from the metadata reader, with annotation defaults already applied
     * @return the described site
     */
    private ConditionSite siteOf(final String className, final String methodName,
            final Map<String, Object> attributes) {
        final String prefix = String.valueOf(attributes.getOrDefault("prefix", ""));
        final List<String> keys = new ArrayList<>();
        keys.addAll(stringsAt(attributes, "name"));
        keys.addAll(stringsAt(attributes, "value"));
        final List<String> qualified = keys.stream()
                .map(key -> prefix.isEmpty() ? key : prefix + "." + key)
                .toList();
        return new ConditionSite(className, methodName, qualified,
                String.valueOf(attributes.getOrDefault("havingValue", "")),
                Boolean.parseBoolean(String.valueOf(attributes.getOrDefault("matchIfMissing", Boolean.FALSE))));
    }

    private List<String> stringsAt(final Map<String, Object> attributes, final String attribute) {
        final Object raw = attributes.get(attribute);
        if (raw instanceof String[] array) {
            return Arrays.asList(array);
        }
        return List.of();
    }

    private AnnotationMetadata readMetadata(final MetadataReaderFactory factory, final Path classFile) {
        try {
            final MetadataReader reader = factory.getMetadataReader(new FileSystemResource(classFile));
            return reader.getAnnotationMetadata();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read class metadata from " + classFile, exception);
        }
    }

    /**
     * Returns every compiled class file under {@link #MAIN_CLASSES}, sorted so failure messages are
     * stable between runs.
     *
     * <p>Returns an empty list when the directory does not exist, rather than throwing. That is
     * deliberate but is <em>not</em> a silent pass: an empty result makes both vacuity guards fail,
     * and their messages name the missing directory. Throwing here would report the same fault in a
     * less specific place.
     *
     * @return the compiled class files, or an empty list when the directory is absent
     */
    private List<Path> compiledClassFiles() {
        if (!Files.isDirectory(MAIN_CLASSES)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(MAIN_CLASSES)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot walk " + MAIN_CLASSES.toAbsolutePath(), exception);
        }
    }

    /**
     * One {@code @ConditionalOnProperty} occurrence.
     *
     * @param className declaring class
     * @param methodName annotated method, or {@code null} when the annotation sits on the type
     * @param propertyKeys effective property keys, prefix already applied
     * @param havingValue the declared {@code havingValue}, empty string when omitted
     * @param matchIfMissing the declared {@code matchIfMissing}, {@code false} when omitted
     */
    private record ConditionSite(String className, String methodName, List<String> propertyKeys,
            String havingValue, boolean matchIfMissing) {

        private boolean classLevel() {
            return methodName == null;
        }

        @Override
        public String toString() {
            final String location = classLevel() ? className : className + "#" + methodName;
            return location + " " + propertyKeys + " havingValue=\"" + havingValue + "\""
                    + " matchIfMissing=" + matchIfMissing;
        }
    }
}
