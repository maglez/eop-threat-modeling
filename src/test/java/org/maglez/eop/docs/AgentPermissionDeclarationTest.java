package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;


/**
 * Holds ADR-065's role boundaries against the agent definitions themselves, so that an agent can no longer acquire a
 * permission by saying nothing about it.
 *
 * <p>OpenCode's effective ruleset begins with an implicit {@code {"permission":"*","action":"allow","pattern":"*"}}
 * and the last matching rule wins, so <strong>omitting a key affirmatively grants it</strong>. That is not a
 * theoretical hazard: it is exactly how the Product Owner came to write seven Java and XML files under
 * {@code src/} and push them, twice — its frontmatter declared {@code task} and four Jira allows and simply never
 * mentioned {@code edit} or {@code bash}. ADR-065 closed that per agent, and recorded as its own known limitation
 * that nothing in {@code ./mvnw verify} walks {@code .opencode/agents/}, so a newly added agent inherits every
 * permission it does not state. This test is that missing walk.
 *
 * <p><strong>What it proves is narrow, and the bound matters.</strong> It proves a key is <em>declared</em>. It says
 * nothing about whether the value is sensible: {@code bash: {"*": allow}} declares {@code bash} and grants
 * everything. Semantic correctness stays reviewer-enforced, exactly as {@code ConditionalOnPropertyHavingValueTest}
 * is bounded to comparing one attribute rather than judging whether a flag gates the right beans. The point is that
 * an author must make a decision and write it down, where a reviewer can see it and disagree.
 *
 * <p>Three facts are read from the working tree rather than hardcoded: which agents exist, what each declares, and
 * whether a catch-all covers it. The two exception lists are declared explicitly below, each carrying the reason it
 * is accepted, on the {@code SeparationInvariantTest} pattern — declared, justified and self-retiring, so closing an
 * exception means deleting its entry rather than leaving it passing over nothing (ADR-006, ADR-052).
 *
 * <p>Surefire runs with the working directory set to the project base directory, so the relative paths resolve.
 *
 * @see <a href="../../../../../../../docs/adr/ADR-065-agent-role-boundaries-at-the-permission-layer.md">ADR-065</a>
 */
@DisplayName("The permission declarations on every OpenCode agent")
class AgentPermissionDeclarationTest {

    /**
     * The directory OpenCode scans for agent definitions. Only {@code *.md} files are agents, which is why the
     * committed {@code .opencode/agents/.opencode} sentinel — a regular file guarding against the nested-config
     * hazard — is excluded naturally rather than by name.
     */
    private static final Path AGENT_DIRECTORY = Path.of(".opencode/agents");

    /** Matches the YAML frontmatter block at the head of an agent definition. */
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\R(.*?)\\R---\\R", Pattern.DOTALL);

    /**
     * The permission keys every agent must take a position on. {@code edit} gates {@code write}, {@code edit} and
     * {@code apply_patch} — there is no {@code write} key, and a rule spelled that way is silently dead config
     * (Blueprint §7.8). {@code bash} gates shell execution, and therefore {@code git commit}, {@code git push} and
     * {@code gh}. These two are the pair the breach ran through.
     */
    private static final List<String> REQUIRED_KEYS = List.of("edit", "bash");

    /**
     * The plugin tools that reach arbitrary execution or arbitrary writes, and therefore defeat {@code bash} and
     * {@code edit} rules if left unstated.
     *
     * <p>{@code run_job} accepts {@code agent}, {@code prompt}, {@code command}, {@code model} and {@code files}
     * overrides, so an agent holding neither {@code bash} nor {@code edit} can still run arbitrary work under a
     * different agent's identity; {@code schedule_job} does the same on a cron trigger; {@code install_skill} writes
     * into {@code .opencode/skill}. The remaining scheduler tools are reads and are deliberately absent from this
     * list.
     */
    private static final List<String> MUTATING_PLUGIN_TOOLS =
            List.of("run_job", "schedule_job", "update_job", "delete_job", "cleanup_global", "install_skill");

    /**
     * The agents that knowingly keep one or more mutating plugin tool, each with the reason.
     *
     * <p>A blanket denial was considered and rejected: it would have broken the scheduled k6 load test, which is the
     * one legitimate use of the job lifecycle in this repository (ADR-065). So the denial is per agent, and these are
     * the agents it does not apply to.
     */
    private static final List<DeclaredException> SCHEDULER_EXCEPTIONS = List.of(
            new DeclaredException(
                    "performance-engineer",
                    "Owns the scheduled k6 load-test job, which is what the job lifecycle exists for here, so "
                            + "schedule_job, run_job, update_job and delete_job are retained. It still denies "
                            + "cleanup_global and install_skill, neither of which is part of running a load test. "
                            + "Closing this entry means moving the load-test schedule somewhere the agent does not "
                            + "drive."),
            new DeclaredException(
                    "tech-lead",
                    "The orchestrator. It is the one agent permitted to dispatch other agents at all (task: allow), "
                            + "so denying it the scheduler would be a boundary drawn in the wrong place — it can "
                            + "already reach any agent through the mechanism designed for it. Closing this entry "
                            + "means deciding the Tech Lead should not schedule work, which is a different design."));

    /**
     * A floor on the number of agent definitions parsed, so no rule below can pass by walking an empty directory —
     * the failure mode ADR-006 records for the branch-coverage limit and {@code MermaidSequenceTextTest} for its
     * diagram count. Seventeen at the time of writing: thirteen delivery agents and four advisory experts. Raise it
     * when an agent is added; lower it only alongside a deliberate decision to remove one.
     */
    private static final int MINIMUM_AGENTS = 17;

    /** The shortest justification accepted on a declared exception, so a placeholder cannot stand in for a reason. */
    private static final int MINIMUM_JUSTIFICATION_LENGTH = 80;

    @Test
    @DisplayName("every agent declares a permission block rather than inheriting the global ruleset wholesale")
    void shouldDeclareAPermissionBlockOnEveryAgent() {
        final List<String> silent = new ArrayList<>();
        readAgents().forEach((name, permission) -> {
            if (permission.isEmpty()) {
                silent.add(name);
            }
        });

        assertThat(silent)
                .as("An agent definition declares no permission block at all, so every tool in the roster is granted "
                        + "to it by the implicit global allow. Add a permission block stating what the agent may do "
                        + "(ADR-065). Offending agents: %s", silent)
                .isEmpty();
    }

    @Test
    @DisplayName("every agent states a position on edit and bash rather than acquiring them by silence")
    void shouldStateEditAndBashOnEveryAgent() {
        final Map<String, List<String>> undeclared = new TreeMap<>();
        readAgents().forEach((name, permission) -> {
            if (isCoveredByDenyAll(permission)) {
                return;
            }
            final List<String> missing = REQUIRED_KEYS.stream().filter(key -> !permission.containsKey(key)).toList();
            if (!missing.isEmpty()) {
                undeclared.put(name, missing);
            }
        });

        assertThat(undeclared)
                .as("An agent leaves a permission unstated, and an unstated permission is granted, not withheld — the "
                        + "effective ruleset opens with an allow-everything baseline and the last matching rule wins. "
                        + "State the key even when the answer is `allow`, so the grant is a decision a reviewer can "
                        + "see rather than an omission nobody notices (ADR-065). A catch-all `\"*\": deny` also "
                        + "satisfies this, which is how the four advisers pass. Offending agents and keys: %s",
                        undeclared)
                .isEmpty();
    }

    @Test
    @DisplayName("denies the plugin tools that would otherwise route around edit and bash")
    void shouldDenyTheMutatingPluginToolsOrDeclareTheException() {
        final Set<String> excepted = SCHEDULER_EXCEPTIONS.stream()
                .map(DeclaredException::agent)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        final Map<String, List<String>> granted = new TreeMap<>();
        readAgents().forEach((name, permission) -> {
            if (excepted.contains(name) || isCoveredByDenyAll(permission)) {
                return;
            }
            final List<String> open = MUTATING_PLUGIN_TOOLS.stream()
                    .filter(tool -> !"deny".equals(permission.get(tool)))
                    .toList();
            if (!open.isEmpty()) {
                granted.put(name, open);
            }
        });

        assertThat(granted)
                .as("An agent can still reach arbitrary execution or arbitrary writes through a plugin tool, which "
                        + "makes its bash and edit rules decorative: run_job takes agent/prompt/command/model "
                        + "overrides and schedule_job cron-runs an arbitrary prompt, both under another agent's "
                        + "identity, and install_skill writes into .opencode/skill. Deny them, or add a justified "
                        + "entry to SCHEDULER_EXCEPTIONS (ADR-065). Offending agents and tools: %s", granted)
                .isEmpty();
    }

    @Test
    @DisplayName("justifies every declared exception rather than silently tolerating it")
    void shouldJustifyEveryDeclaredException() {
        assertThat(SCHEDULER_EXCEPTIONS)
                .as("The exception list is the only sanctioned way to keep a mutating plugin tool, so it must not be "
                        + "empty while the scheduled load-test job exists — see ADR-065.")
                .isNotEmpty();

        for (final DeclaredException declared : SCHEDULER_EXCEPTIONS) {
            assertThat(declared.justification())
                    .as("The declared exception for @%s carries no usable justification. An accepted exception must "
                            + "record why it is accepted and what closing it would take, or it decays into an "
                            + "unexplained hole.", declared.agent())
                    .isNotBlank()
                    .hasSizeGreaterThanOrEqualTo(MINIMUM_JUSTIFICATION_LENGTH);
        }
    }

    @Test
    @DisplayName("forces a closed exception to be deleted rather than left naming an agent that no longer exists")
    void shouldNotDeclareAnExceptionForAnAgentThatIsGone() {
        final Set<String> agents = readAgents().keySet();

        for (final DeclaredException declared : SCHEDULER_EXCEPTIONS) {
            assertThat(agents)
                    .as("SCHEDULER_EXCEPTIONS still exempts @%s, but no such agent definition exists. Delete the "
                            + "entry instead of leaving it exempting nothing; a guard that can no longer fire must be "
                            + "fixed or deleted, never left green (ADR-006).", declared.agent())
                    .contains(declared.agent());
        }
    }

    @Test
    @DisplayName("is actually reading the definitions it guards, so none of the rules above can pass over nothing")
    void shouldFindTheAgentsItGuards() {
        final Map<String, Map<String, Object>> agents = readAgents();

        assertThat(agents)
                .as("No agent definitions were parsed out of %s — is the working directory the project root?",
                        AGENT_DIRECTORY)
                .isNotEmpty()
                .hasSizeGreaterThanOrEqualTo(MINIMUM_AGENTS);

        assertThat(agents.keySet())
                .as("The sentinel file .opencode/agents/.opencode must never be read as an agent. It is a regular "
                        + "file, deliberately, so that launching OpenCode from the wrong directory fails fast instead "
                        + "of registering phantom agents.")
                .doesNotContain(".opencode");
    }

    @Test
    @DisplayName("detects an undeclared permission when fed one, proving the rules fire independently of the tree")
    void shouldDetectSilenceInASyntheticDefinition() {
        final Map<String, Object> silent = Map.of("task", "deny");
        final Map<String, Object> stated = Map.of("edit", "deny", "bash", "deny");
        final Map<String, Object> catchAll = Map.of("*", "deny");

        assertThat(REQUIRED_KEYS.stream().filter(key -> !silent.containsKey(key)).toList())
                .as("A definition stating only `task` must be reported as missing both edit and bash. If this fails, "
                        + "the detector is broken and every green run of the rules above is meaningless.")
                .containsExactly("edit", "bash");

        assertThat(REQUIRED_KEYS.stream().filter(key -> !stated.containsKey(key)).toList())
                .as("A definition stating both keys must not be reported.")
                .isEmpty();

        assertThat(isCoveredByDenyAll(catchAll))
                .as("A catch-all `\"*\": deny` must be recognised as covering the required keys, which is what makes "
                        + "the four advisers' allow-list formulation legal rather than an omission.")
                .isTrue();

        assertThat(isCoveredByDenyAll(silent))
                .as("A definition with no catch-all must not be treated as covered.")
                .isFalse();
    }

    /**
     * Reads every agent definition and returns its declared permission block, keyed by agent name.
     *
     * @return agent name to permission block, in a stable order; an agent with no block maps to an empty map
     */
    private static Map<String, Map<String, Object>> readAgents() {
        final Map<String, Map<String, Object>> agents = new LinkedHashMap<>();
        try (Stream<Path> definitions = Files.list(AGENT_DIRECTORY)) {
            definitions.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(path -> agents.put(agentName(path), readPermission(path)));
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot list " + AGENT_DIRECTORY, cause);
        }
        return agents;
    }

    /**
     * Derives the agent name OpenCode uses from a definition path.
     *
     * @param definition the path of an agent definition
     * @return the file name without its {@code .md} suffix
     */
    private static String agentName(final Path definition) {
        final String fileName = definition.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".md".length());
    }

    /**
     * Parses the {@code permission} mapping out of a definition's YAML frontmatter.
     *
     * @param definition the path of an agent definition
     * @return the declared permission block, or an empty map when none is declared
     */
    private static Map<String, Object> readPermission(final Path definition) {
        final String text;
        try {
            text = Files.readString(definition);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + definition, cause);
        }

        final Matcher matcher = FRONTMATTER.matcher(text);
        if (!matcher.find()) {
            return Map.of();
        }

        final Object parsed = new Yaml().load(matcher.group(1));
        if (!(parsed instanceof Map<?, ?> frontmatter)) {
            return Map.of();
        }

        final Object permission = frontmatter.get("permission");
        if (!(permission instanceof Map<?, ?> block)) {
            return Map.of();
        }

        final Map<String, Object> declared = new LinkedHashMap<>();
        block.forEach((key, value) -> declared.put(String.valueOf(key), value));
        return declared;
    }

    /**
     * Reports whether a permission block opens with a catch-all denial, which withholds every tool the block does not
     * subsequently re-allow.
     *
     * <p>This is the four advisers' formulation, and it is preferred to an enumerated deny-list precisely because a
     * list of today's tool names silently grants tomorrow's.
     *
     * @param permission a declared permission block
     * @return {@code true} when a {@code "*": deny} rule is present
     */
    private static boolean isCoveredByDenyAll(final Map<String, Object> permission) {
        return "deny".equals(permission.get("*"));
    }

    /**
     * A knowingly accepted exception to one of the rules above, carrying the reason it is accepted.
     *
     * @param agent the agent name the exception applies to
     * @param justification why it is accepted, and what closing it would take
     */
    private record DeclaredException(String agent, String justification) {
    }
}
