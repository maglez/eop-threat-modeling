package org.maglez.eop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds every {@code eop.features.*} flag in three-way agreement between the shipped
 * {@code application.yml}, the {@code @ConditionalOnProperty} sites in the compiled bytecode, and
 * the declared intent in {@code src/test/resources/feature-flag-registry.yml} (EOP-84, ADR-053).
 *
 * <p><strong>Why the Spring {@code Environment} cannot be read here.</strong> Every flag is pinned
 * {@code true} in {@code src/test/resources/application.properties}, because a suite running with a
 * feature off would be testing its absence; {@code .opencode/rules/feature-flags.md} requires that
 * pin and it must not be removed. But it creates a blind spot: any test that reads the Spring
 * {@link org.springframework.core.env.Environment} sees the test-resource value, not the shipped
 * default, so an assertion like {@code environment.getProperty("eop.features.trick-play")} passes
 * even when the flag is still {@code false} in {@code application.yml}. That is exactly the trap
 * that caused EOP-82: the flag was {@code false} in the shipped YAML while the suite ran green,
 * and a finished feature shipped dark for two stories. This class therefore reads
 * {@code application.yml} directly from the classpath through {@link YamlPropertiesFactoryBean} —
 * the same Spring API the application itself binds the file with — and asserts the raw value
 * before any test-resource override is applied. SnakeYAML is already on the classpath transitively
 * through {@code spring-boot-starter}, so no dependency is added. There is no Spring context here
 * and the class runs in milliseconds.
 *
 * <p>The pattern is deliberately different from {@link H2ConsoleAbsentIntegrationTest}, which reads
 * the {@code Environment} and is correct to, because {@code spring.h2.console.enabled} is
 * <em>not</em> pinned in test resources. Copying that pattern here would produce a test that always
 * passes for the wrong reason. A future reader who wants to "simplify" this class into an
 * {@code Environment} lookup should read this paragraph first.
 *
 * <p><strong>Why the assertion is derived rather than hand-written.</strong> This class replaces
 * {@code ShippedFeatureFlagDefaultsTest}, which asserted three hand-named keys. That guard detected
 * the recurrence of one instance rather than the class of fault: a fourth flag was invisible to it,
 * so the next feature to ship dark would have done so just as quietly. ADR-042 recorded it as an
 * interim workaround and specified this replacement. The direction of the assertion is inverted —
 * the key set is derived from the artefacts that actually exist, and checked against a registry
 * that declares intent, so the build fails <em>until</em> someone declares a new flag rather than
 * staying silent unless someone remembers to add a line. That is what makes it a tripwire rather
 * than a snapshot, in the sense of EOP-27's {@link H2ConsoleAbsentIntegrationTest}.
 *
 * <p>Bytecode is scanned rather than source text, for the reasons {@link
 * ConditionalOnPropertyHavingValueTest} records: annotation formatting, attribute order, comments
 * and line wrapping are all invisible to a metadata reader, and Spring applies the annotation's
 * declared defaults on the way out, so the scan proves what the container will see rather than what
 * a regular expression happened to match.
 *
 * <p>Both remaining halves of the guard are honest about their limits. A registry entry is still
 * hand-editable, so this does not remove the need for review; what it removes is the possibility of
 * a flag existing without anyone having written down its intended state, its owner and its expiry.
 * And an expiry that passes turns the build red on a calendar boundary — that is the intended
 * signal, accepted deliberately in ADR-053 on the precedent of
 * {@code tools/supply-chain/accepted-advisories.json}, which likewise fails in both directions.
 */
@DisplayName("ADR-053: eop.features flags agree across application.yml, bytecode and the registry")
class FeatureFlagRegistryTest {

    /** Namespace every feature flag lives under, including the trailing dot. */
    private static final String FLAG_PREFIX = "eop.features.";

    /** The shipped configuration, read as a classpath resource rather than through the Environment. */
    private static final String SHIPPED_YAML = "application.yml";

    /** The declaration of intent, one entry per flag. */
    private static final String REGISTRY = "feature-flag-registry.yml";

    /** Top-level key holding the registry's list of entries. */
    private static final String FLAGS = "flags";

    /**
     * Fields every registry entry must carry, no more and no fewer.
     *
     * <p>Exactness in both directions is what catches a typo. A misspelled {@code shipped-defualt}
     * would otherwise read as an absent {@code shipped-default} plus a harmless extra field, and a
     * misspelled {@code expriy} would silently mean "no expiry declared" — the failure mode this
     * whole class exists to remove.
     */
    private static final List<String> REQUIRED_FIELDS = List.of("key", "shipped-default", "owner-story", "expiry");

    /** Field naming the property. */
    private static final String FIELD_KEY = "key";

    /** Field naming the value {@code application.yml} must carry. */
    private static final String FIELD_SHIPPED_DEFAULT = "shipped-default";

    /** Field naming the Jira story that owns the flag's current position. */
    private static final String FIELD_OWNER_STORY = "owner-story";

    /** Field naming the date after which the flag must be gone, or {@code null}. */
    private static final String FIELD_EXPIRY = "expiry";

    /**
     * Fully qualified name of the annotation that gates a flagged bean, held as text.
     *
     * <p>As a string rather than an import because a test in the interface-adapter package has no
     * business importing an autoconfiguration type it never calls, and because the metadata reader
     * identifies annotations by name anyway.
     */
    private static final String CONDITIONAL_ON_PROPERTY =
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty";

    /**
     * Compiled output of the main source set.
     *
     * <p>A directory walk rather than a {@code classpath*:} pattern, for the reason {@link
     * ConditionalOnPropertyHavingValueTest} gives: the pattern would also sweep
     * {@code target/test-classes} and every dependency jar, auditing code this project does not
     * own. Surefire's working directory is the project base directory, so the relative path
     * resolves, and {@code compile} precedes {@code test}, so the directory is populated. Neither
     * assumption is trusted — the vacuity guard below fails loudly if the walk finds nothing.
     */
    private static final Path MAIN_CLASSES = Path.of("target", "classes");

    /**
     * Today, in UTC.
     *
     * <p>Fixed to UTC rather than the default zone so that the build's verdict does not depend on
     * which side of midnight the machine running it happens to be. An expiry is a date in a
     * document, not an instant, and a flag whose expiry passes at a different hour in CI than on a
     * laptop would be worse than one that never expires.
     */
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Test
    @DisplayName("every flag in the shipped application.yml is declared in the registry, and vice versa")
    void shouldAgreeBetweenShippedYamlAndRegistry() {
        assertThat(shippedFlagKeys())
                .as("The flags in %s and the entries in %s must be the same set (ADR-053).%n"
                        + "A key in the YAML with no registry entry is an undeclared flag: nothing records its"
                        + " intended state, its owner or its expiry, so the next reader has no way to tell a"
                        + " deliberate default from an oversight — the EOP-82 fault.%n"
                        + "An entry in the registry with no key in the YAML is a stale declaration, or a"
                        + " misspelled one. It is not a legitimate resting state even mid-removal: ADR-042"
                        + " requires a flag and every one of its guards to be deleted in the same commit, so"
                        + " the two sets are only ever unequal within a single unfinished edit.",
                        SHIPPED_YAML, REGISTRY)
                .containsExactlyInAnyOrderElementsOf(registryKeys());
    }

    @Test
    @DisplayName("every @ConditionalOnProperty flag key in the bytecode is declared in the registry, and vice versa")
    void shouldAgreeBetweenBytecodeAndRegistry() {
        assertThat(gatedFlagKeys())
                .as("The eop.features.* keys read off the compiled @ConditionalOnProperty sites and the"
                        + " entries in %s must be the same set (ADR-053).%n"
                        + "An annotation whose key has no registry entry is the fail-closed trap: with no"
                        + " declared default the flag ships OFF, the routes answer the framework's own 404, and"
                        + " nothing in the suite notices because the test-resource override pins the flag ON.%n"
                        + "A registry entry with no annotation is a flag that gates nothing — either a dead key"
                        + " or a typo in the annotation, both of which read to a reviewer as protection that is"
                        + " not there.", REGISTRY)
                .containsExactlyInAnyOrderElementsOf(registryKeys());
    }

    @Test
    @DisplayName("the shipped value of every flag equals the intent its registry entry declares")
    void shouldMatchShippedValueToDeclaredIntent() {
        final Properties shipped = loadShippedYaml();
        final List<String> disagreements = new ArrayList<>();
        for (final FlagDeclaration flag : declarations()) {
            final String actual = shipped.getProperty(flag.key());
            final String intended = String.valueOf(flag.shippedDefault());
            if (!intended.equals(actual)) {
                disagreements.add(flag.key() + ": " + SHIPPED_YAML + " says " + actual
                        + ", " + REGISTRY + " declares " + intended);
            }
        }

        assertThat(disagreements)
                .as("A flag whose shipped value disagrees with its declared intent is the EOP-82 fault"
                        + " itself: the feature is complete, the registry says it should be live, and the"
                        + " artefact that actually ships says otherwise. Fix whichever of the two is wrong —"
                        + " but if the intent has genuinely changed, change it in %s under review, so the"
                        + " decision is recorded rather than inferred from a value nobody chose.", REGISTRY)
                .isEmpty();
    }

    @Test
    @DisplayName("no flag has passed the expiry its registry entry declares")
    void shouldFailOnceAFlagPassesItsExpiry() {
        final List<String> expired = new ArrayList<>();
        for (final FlagDeclaration flag : declarations()) {
            flag.expiry()
                    .filter(TODAY::isAfter)
                    .ifPresent(date -> expired.add(flag.key() + " expired on " + date
                            + " (owner " + flag.ownerStory() + ", today is " + TODAY + ")"));
        }

        assertThat(expired)
                .as("This failure is the tripwire, not a defect in it. ADR-042 recorded three flags flipped"
                        + " on, three promises to remove them and zero removals, and concluded that prose"
                        + " commitments are not a lifecycle mechanism. An expiry date in %s is that mechanism.%n"
                        + "There are exactly two correct responses, and both are reviewed changes: remove the"
                        + " flag together with every @ConditionalOnProperty that names it, its key in %s, its"
                        + " line in src/test/resources/application.properties and its OFF-position integration"
                        + " test — that test asserts the beans are absent, not merely that the routes 404, so"
                        + " leaving it behind turns the build red rather than leaving it quietly green. Read"
                        + " those failures as the leftover OFF-position test they are and delete it; do not"
                        + " relax its assertions (ADR-042, corrected 2026-08-23) — or extend the date here"
                        + " with the reason. Deleting the"
                        + " expiry field is not one of them.", REGISTRY, SHIPPED_YAML)
                .isEmpty();
    }

    @Test
    @DisplayName("every registry entry carries exactly the four declared fields, expiry included")
    void shouldRequireEveryFieldOnEveryEntry() {
        final List<String> malformed = new ArrayList<>();
        for (final Map<String, Object> entry : rawEntries()) {
            if (!entry.keySet().containsAll(REQUIRED_FIELDS) || entry.size() != REQUIRED_FIELDS.size()) {
                malformed.add(String.valueOf(entry.get(FIELD_KEY)) + " declares " + entry.keySet());
            }
        }

        assertThat(malformed)
                .as("Every entry in %s must carry exactly %s.%n"
                        + "The expiry field is required even when there is no expiry: `expiry: null` declares"
                        + " that no date has been authorised, which is a decision, whereas omitting the key"
                        + " lets a new flag inherit no-expiry by silence — the habit ADR-042 condemned.%n"
                        + "Surplus fields fail too, because that is what catches a misspelling: `shipped-defualt`"
                        + " would otherwise read as an absent shipped-default plus a harmless extra key.",
                        REGISTRY, REQUIRED_FIELDS)
                .isEmpty();
    }

    @Test
    @DisplayName("every registry entry names an owning story and a key inside the eop.features namespace")
    void shouldRequireAWellFormedKeyAndOwner() {
        final List<String> offenders = new ArrayList<>();
        for (final FlagDeclaration flag : declarations()) {
            if (!flag.key().startsWith(FLAG_PREFIX) || flag.key().length() == FLAG_PREFIX.length()) {
                offenders.add(flag.key() + ": key must start with " + FLAG_PREFIX + " and name a flag after it");
            }
            if (!flag.ownerStory().matches("EOP-\\d+")) {
                offenders.add(flag.key() + ": owner-story must be a Jira key of the form EOP-NNN, not "
                        + flag.ownerStory());
            }
        }

        assertThat(offenders)
                .as("A flag outside the %s namespace is not a feature flag: caching.md reserves eop.* directly"
                        + " for infrastructure toggles, and this registry does not govern those. An owner-story"
                        + " that is not a Jira key cannot be looked up, which defeats the point of recording"
                        + " one — the reason a flag holds its current position is the first thing anyone"
                        + " deciding whether to remove it needs.", FLAG_PREFIX)
                .isEmpty();
    }

    @Test
    @DisplayName("no flag is declared twice in the registry")
    void shouldDeclareEachFlagOnce() {
        assertThat(registryKeys())
                .as("Two entries for one flag give it two declared intents, and %s would then be checked"
                        + " against whichever the parser happened to reach — the same ambiguity"
                        + " AdrIndexConsistencyTest refuses for a duplicated ADR row.", SHIPPED_YAML)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("vacuity guard: flag keys are actually derived from the shipped application.yml")
    void shouldDeriveFlagKeysFromTheShippedYaml() {
        assertThat(shippedFlagKeys())
                .as("If this is empty the derivation found nothing and every assertion above is vacuous:"
                        + " comparing two empty sets passes. Either %s is no longer on the test classpath, or"
                        + " the eop.features block was renamed or removed. The two keys expected today are"
                        + " eop.features.session-lifecycle and eop.features.trick-play.",
                        SHIPPED_YAML)
                .isNotEmpty();
    }

    @Test
    @DisplayName("vacuity guard: flag keys are actually derived from the compiled bytecode")
    void shouldDeriveFlagKeysFromTheBytecode() {
        assertThat(gatedFlagKeys())
                .as("If this is empty the bytecode scan found no @ConditionalOnProperty naming an %s key, so"
                        + " the agreement assertion above is comparing an empty set against the registry and"
                        + " passing for the wrong reason. ConditionalOnPropertyHavingValueTest is the precedent"
                        + " scanner and finds 21 sites across 7 files, so a working scan is not in doubt — check"
                        + " that %s exists and that the compile phase ran.",
                        FLAG_PREFIX, MAIN_CLASSES.toAbsolutePath())
                .isNotEmpty();
    }

    @Test
    @DisplayName("vacuity guard: the registry actually parses to at least one entry")
    void shouldParseAtLeastOneRegistryEntry() {
        assertThat(rawEntries())
                .as("If this is empty the registry contributed nothing and every comparison above is between"
                        + " two empty sets. Check that src/test/resources/%s exists, that its top-level key is"
                        + " still `%s`, and that the list under it is not empty. An empty registry is never"
                        + " correct while any flag exists.", REGISTRY, FLAGS)
                .isNotEmpty();
    }

    /**
     * Returns every {@code eop.features.*} key in the shipped {@code application.yml}.
     *
     * <p>Filtered to the flag namespace on purpose: the same file carries {@code eop.web.*} and
     * {@code eop.sweep.*} infrastructure keys, which {@code caching.md} places outside
     * {@code eop.features} deliberately and which this registry does not govern.
     *
     * <p>The key set is read through {@link Properties#keySet()} rather than the more obvious
     * {@link Properties#stringPropertyNames()}, and the difference is not cosmetic. Spring returns
     * a string-adapting {@code Properties} that stores each YAML scalar as its parsed type and
     * converts only on {@code getProperty}, so a boolean flag is held as a {@link Boolean}; but
     * {@code stringPropertyNames()} contractually admits a key only when <em>its value is also a
     * String</em>, so it silently omitted every flag. That yielded an empty set here while
     * {@code getProperty} kept working, which is how the mistake nearly passed as correct: the
     * agreement assertion compared an empty set against the registry and reported the registry's
     * own entries as missing. The vacuity guard is what named it.
     *
     * @return the flag keys, sorted
     */
    private List<String> shippedFlagKeys() {
        return loadShippedYaml().keySet().stream()
                .map(String::valueOf)
                .filter(key -> key.startsWith(FLAG_PREFIX))
                .sorted()
                .toList();
    }

    /**
     * Loads {@code application.yml} from the classpath as a flat {@link Properties} map, with the
     * YAML hierarchy flattened to dotted keys exactly as Spring Boot flattens it at startup.
     *
     * @return the shipped properties, never {@code null}
     */
    private Properties loadShippedYaml() {
        final YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(SHIPPED_YAML));
        final Properties properties = factory.getObject();
        assertThat(properties)
                .as("%s must be loadable from the classpath — without it there is no shipped value to"
                        + " compare anything against", SHIPPED_YAML)
                .isNotNull();
        return properties;
    }

    /**
     * Returns every distinct {@code eop.features.*} property key named by a
     * {@code @ConditionalOnProperty} in the compiled main source set.
     *
     * @return the gated flag keys, sorted, without duplicates
     */
    private List<String> gatedFlagKeys() {
        final MetadataReaderFactory factory = new SimpleMetadataReaderFactory();
        final List<String> keys = new ArrayList<>();
        for (final Path classFile : compiledClassFiles()) {
            final AnnotationMetadata metadata = readMetadata(factory, classFile);
            if (metadata.hasAnnotation(CONDITIONAL_ON_PROPERTY)) {
                collectFlagKeys(metadata.getAnnotationAttributes(CONDITIONAL_ON_PROPERTY), keys);
            }
            for (final MethodMetadata method : metadata.getAnnotatedMethods(CONDITIONAL_ON_PROPERTY)) {
                collectFlagKeys(method.getAnnotationAttributes(CONDITIONAL_ON_PROPERTY), keys);
            }
        }
        return keys.stream().distinct().sorted().toList();
    }

    /**
     * Resolves the effective property keys of one annotation site and adds those in the flag
     * namespace to the given accumulator.
     *
     * <p>The {@code name} and {@code value} attributes are declared aliases, so
     * {@code @ConditionalOnProperty("eop.features.trick-play")} populates {@code value} and leaves
     * {@code name} empty; both are read and unioned. {@code prefix} is joined back on with a dot,
     * because both spellings are in use in this repository — {@code prefix = "eop.features", name =
     * "trick-play"} and the fully dotted {@code name = "eop.features.trick-play"} — and only the
     * effective, operator-settable key can be compared with the YAML.
     *
     * @param attributes the annotation's attributes, with declared defaults already applied
     * @param keys accumulator the resolved flag keys are added to
     */
    private void collectFlagKeys(final Map<String, Object> attributes, final List<String> keys) {
        if (attributes == null) {
            return;
        }
        final String prefix = String.valueOf(attributes.getOrDefault("prefix", ""));
        final List<String> declared = new ArrayList<>(stringsAt(attributes, "name"));
        declared.addAll(stringsAt(attributes, "value"));
        for (final String key : declared) {
            final String qualified = prefix.isEmpty() ? key : prefix + "." + key;
            if (qualified.startsWith(FLAG_PREFIX)) {
                keys.add(qualified);
            }
        }
    }

    /**
     * Reads one string-array annotation attribute, tolerating its absence.
     *
     * @param attributes the annotation's attributes
     * @param attribute the attribute name to read
     * @return the attribute's values, or an empty list when it is absent or not a string array
     */
    private List<String> stringsAt(final Map<String, Object> attributes, final String attribute) {
        final Object raw = attributes.get(attribute);
        return raw instanceof String[] array ? Arrays.asList(array) : List.of();
    }

    /**
     * Reads the annotation metadata of one compiled class.
     *
     * @param factory the reader factory to use
     * @param classFile the {@code .class} file to read
     * @return that class's annotation metadata
     */
    private AnnotationMetadata readMetadata(final MetadataReaderFactory factory, final Path classFile) {
        try {
            return factory.getMetadataReader(new FileSystemResource(classFile)).getAnnotationMetadata();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read class metadata from " + classFile, exception);
        }
    }

    /**
     * Returns every {@code .class} file under {@link #MAIN_CLASSES}.
     *
     * <p>An absent directory yields an empty list rather than an exception. That is not a silent
     * pass: the vacuity guard above then fails, naming the missing directory, which is a better
     * diagnostic than a stack trace from a walk over a path that does not exist.
     *
     * @return the compiled class files, sorted so that failure messages are stable
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
     * Returns the key of every registry entry, in declaration order and including duplicates.
     *
     * <p>Duplicates are preserved rather than collapsed so that {@link #shouldDeclareEachFlagOnce()}
     * can see them; the agreement assertions compare sets and are unaffected.
     *
     * <p>Deliberately <em>not</em> sorted, unlike {@link #shippedFlagKeys()} and
     * {@link #gatedFlagKeys()}, which sort for stable failure messages. Those two derive their order
     * from a hash-ordered {@link Properties} and a filesystem walk respectively, so sorting is what
     * makes them reproducible. This one reads a hand-maintained file top to bottom, so declaration
     * order is already deterministic <em>and</em> more useful: a message listing keys in the order
     * they appear points a reader at the line to edit. Every consumer is order-independent
     * ({@code containsExactlyInAnyOrderElementsOf}, {@code doesNotHaveDuplicates}), so the sort this
     * method once carried bought nothing and contradicted the contract stated above — a prose claim
     * disagreeing with its own code, in the one story whose purpose is removing those. Caught by
     * @code-reviewer's second gate round.
     *
     * @return the declared flag keys
     */
    private List<String> registryKeys() {
        return rawEntries().stream().map(entry -> String.valueOf(entry.get(FIELD_KEY))).toList();
    }

    /**
     * Returns the registry's entries as typed declarations, having validated each field's type.
     *
     * @return one declaration per registry entry, in declaration order
     */
    private List<FlagDeclaration> declarations() {
        final List<FlagDeclaration> declarations = new ArrayList<>();
        for (final Map<String, Object> entry : rawEntries()) {
            declarations.add(new FlagDeclaration(
                    String.valueOf(entry.get(FIELD_KEY)),
                    shippedDefaultOf(entry),
                    String.valueOf(entry.get(FIELD_OWNER_STORY)),
                    expiryOf(entry)));
        }
        return declarations;
    }

    /**
     * Reads one entry's declared shipped default, requiring an actual YAML boolean.
     *
     * <p>The type is checked rather than coerced, and the direction of the coercion is why. A
     * tolerant read such as {@code Boolean.TRUE.equals(raw)} answers {@code false} for anything
     * that is not the boolean {@code true} — so a typo like {@code shipped-defualt: ture} or
     * {@code shipped-default: flase} arrives as a {@code String} and reads back as a deliberate
     * declaration of {@code false}. In a fail-closed control that is the wrong way to fail: the
     * mistake is silently reinterpreted as "this flag is meant to ship dark", which is a sentence
     * nobody wrote. Worse, it can agree with a matching typo in {@code application.yml} — Spring
     * treats any value but the literal {@code true} as disabled — and the pair would then pass this
     * guard while the flag ships OFF, which is precisely the EOP-82 fault the class exists to catch.
     *
     * <p>{@code shouldRequireEveryFieldOnEveryEntry} catches a misspelled field *name*, because the
     * field set is compared in both directions. It cannot catch a misspelled *value*, because the
     * key is spelled correctly and only the scalar is wrong. Hence this check, which mirrors the
     * type assertion {@code expiryOf} already makes for its own field.
     *
     * @param entry one registry entry
     * @return the declared shipped default
     */
    private boolean shippedDefaultOf(final Map<String, Object> entry) {
        final Object raw = entry.get(FIELD_SHIPPED_DEFAULT);
        assertThat(raw)
                .as("%s of %s must be an unquoted YAML boolean (true or false), not a %s."
                        + " A quoted \"true\" or a typo such as flase parses as a string, and a tolerant"
                        + " read would silently treat it as false — declaring the flag dark, which is not"
                        + " what was written. Fix the value in %s.",
                        FIELD_SHIPPED_DEFAULT, entry.get(FIELD_KEY),
                        raw == null ? "null" : raw.getClass().getName(), REGISTRY)
                .isInstanceOf(Boolean.class);
        return (Boolean) raw;
    }

    /**
     * Reads one entry's expiry.
     *
     * <p>Only a quoted ISO string or the literal {@code null} is accepted. An unquoted
     * {@code 2026-09-18} is resolved by SnakeYAML to a {@link java.util.Date}, which would either
     * throw somewhere less informative or — worse — be coerced into a date this guard never
     * compared, so it is rejected here with the fix in the message.
     *
     * @param entry one registry entry
     * @return the declared expiry, empty when the entry declares {@code null}
     */
    private Optional<LocalDate> expiryOf(final Map<String, Object> entry) {
        final Object raw = entry.get(FIELD_EXPIRY);
        if (raw == null) {
            return Optional.empty();
        }
        assertThat(raw)
                .as("%s of %s must be a QUOTED ISO date such as \"2026-09-18\", or the literal null."
                        + " Unquoted, YAML resolves it to a %s rather than a string, so the date this guard"
                        + " compares would not be the date you wrote.",
                        FIELD_EXPIRY, entry.get(FIELD_KEY), raw.getClass().getName())
                .isInstanceOf(String.class);
        try {
            return Optional.of(LocalDate.parse((String) raw));
        } catch (final DateTimeParseException exception) {
            throw new IllegalStateException(FIELD_EXPIRY + " of " + entry.get(FIELD_KEY)
                    + " is not an ISO date: " + raw, exception);
        }
    }

    /**
     * Parses the registry into one string-keyed map per entry.
     *
     * <p>The raw maps are exposed rather than only the typed declarations because the difference
     * between an absent key and a key whose value is {@code null} is load-bearing here:
     * {@code expiry: null} is a legitimate declaration of "no expiry authorised", while omitting
     * {@code expiry} altogether must fail the build. A flattened property view collapses that
     * distinction, so SnakeYAML is used directly.
     *
     * @return the entries, in declaration order
     */
    private List<Map<String, Object>> rawEntries() {
        final Object document = loadRegistry();
        if (!(document instanceof Map<?, ?> root)) {
            throw new IllegalStateException(REGISTRY + " must be a YAML mapping with a top-level `"
                    + FLAGS + "` key, but parsed to " + describe(document));
        }
        final Object flags = root.get(FLAGS);
        if (flags == null) {
            throw new IllegalStateException(REGISTRY + " has no top-level `" + FLAGS + "` key");
        }
        if (!(flags instanceof List<?> list)) {
            throw new IllegalStateException(REGISTRY + "'s `" + FLAGS + "` must be a list of entries, but is "
                    + describe(flags));
        }
        final List<Map<String, Object>> entries = new ArrayList<>();
        for (final Object element : list) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(REGISTRY + " entry " + entries.size() + " must be a mapping, but is "
                        + describe(element));
            }
            final Map<String, Object> fields = new LinkedHashMap<>();
            entry.forEach((name, value) -> fields.put(String.valueOf(name), value));
            entries.add(fields);
        }
        return entries;
    }

    /**
     * Loads the registry document from the test classpath.
     *
     * @return whatever SnakeYAML parses the file to, possibly {@code null} for an empty file
     */
    private Object loadRegistry() {
        try (InputStream stream = new ClassPathResource(REGISTRY).getInputStream()) {
            return new Yaml().load(stream);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot read src/test/resources/" + REGISTRY, exception);
        }
    }

    /**
     * Describes a parsed YAML value for a failure message, without printing its contents.
     *
     * @param value the value to describe, possibly {@code null}
     * @return a short type description
     */
    private String describe(final Object value) {
        return value == null ? "null (is the file empty?)" : "a " + value.getClass().getSimpleName();
    }

    /**
     * One registry entry: a flag, the state it is meant to ship in, who owns that decision, and
     * when the flag must be gone.
     *
     * @param key the fully qualified property, always prefixed {@code eop.features.}
     * @param shippedDefault the value {@code application.yml} must carry
     * @param ownerStory the Jira key of the story that owns the flag's current position
     * @param expiry the date after which the flag must no longer exist, empty when none is declared
     */
    private record FlagDeclaration(String key, boolean shippedDefault, String ownerStory, Optional<LocalDate> expiry) {
    }
}
