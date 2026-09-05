package org.maglez.eop.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the six {@code bash} deny rules that keep scratch work inside the worktree, so that the {@code .tmp/}
 * convention cannot quietly revert to being prose.
 *
 * <p>The convention itself is old; the enforcement is not. {@code AGENTS.md} asked for {@code .tmp/} and justified it
 * with a claim that turned out to be false — that OpenCode's unconfigured {@code external_directory} permission makes
 * every touch of {@code /tmp} block on a prompt. It does not: {@code external_directory} gates the <em>file</em>
 * tools, and a heredoc or a {@code >} redirect from a shell command was evaluated against the {@code bash} map alone,
 * where {@code "*": "allow"} let it through. On 2026-09-05 an agent that had read the rule wrote nine scratch files
 * to {@code /tmp} in one day, each logged {@code action.pattern=* action.action=allow}. The six deny entries at the
 * end of {@code permission.bash} are the enforcement that was missing, and this test is what stops them being
 * deleted by a later hand that reads them as noise (Blueprint §7.8, §7.9).
 *
 * <p><strong>What it proves is narrow, and the bound matters.</strong> It reads a committed configuration file. It
 * cannot observe an agent's shell commands, so it proves the rules are <em>declared</em> and never that one fired —
 * the firing is evidenced by the quoted log lines in Blueprint §7.9, gathered once by hand. That is the same split as
 * {@code AgentPermissionDeclarationTest}, which proves a permission key is declared without judging its value.
 *
 * <p>Beyond presence it resolves representative commands through a local mirror of OpenCode's matcher — each pattern
 * to an anchored regex with {@code *} as {@code .*}, last matching rule wins — because presence alone would not
 * notice a later rule appended below the denies that re-allowed the paths they close. The mirror is validated against
 * the two behaviours actually observed in the log: {@code ls -la /tmp/a.txt} resolving to
 * <code>action.pattern=&#42;/tmp/&#42; action.action=deny</code>, and {@code rm -f .tmp/permcheck/throwaway.txt} to
 * {@code action.pattern="rm -f .tmp/*" action.action=allow}.
 *
 * <p>The positive control is the more important half. A deny that also caught {@code .tmp/} would be worse than no
 * deny at all, since it would forbid the only sanctioned scratch directory; the {@code .tmp/} cases below fail the
 * build if that ever becomes true.
 *
 * <p>Surefire runs with the working directory set to the project base directory, so the relative path resolves.
 */
@DisplayName("The OpenCode bash deny rules that keep scratch work in .tmp/")
class OpencodeTmpDenyRulesTest {

    /** The configuration file OpenCode reads at process start. */
    private static final Path CONFIG = Path.of(".opencode/opencode.json");

    /**
     * The six patterns that must stay denied, each with the reason it is not redundant.
     *
     * <p><code>&#42;/tmp/&#42;</code> covers the common case and, because it needs only the literal substring
     * {@code /tmp/}, covers {@code /private/tmp/} and {@code /var/tmp/} for free — which is why no separate entry for
     * either exists. <code>&#42;/tmp</code> and <code>&#42;/tmp &#42;</code> exist because a trailing-{@code *}
     * pattern cannot match a command that ends at {@code /tmp} or passes it as a bare argument, which is the exact
     * shape of the violation that prompted this: {@code javac /tmp/assertion_probe.java -d /tmp}.
     * <code>&#42;/var/folders/&#42;</code> closes the per-user temporary directory that the agent's own bash tool
     * briefing recommends as "pre-approved" — a claim already falsified once in Blueprint §7.8. {@code *$TMPDIR*}
     * closes the same directory reached through the environment variable, and {@code *mktemp*} closes it reached
     * through command substitution such as {@code d=$(mktemp -d)}.
     */
    private static final List<String> REQUIRED_DENIES =
            List.of("*/tmp/*", "*/tmp", "*/tmp *", "*/var/folders/*", "*$TMPDIR*", "*mktemp*");

    /**
     * Commands that must resolve to {@code deny}. The first three are the real violations of 2026-09-05, taken from
     * the log rather than invented.
     */
    private static final List<String> MUST_BE_DENIED = List.of(
            "ls -la /tmp/a.txt",
            "javac /tmp/assertion_probe.java -d /tmp",
            "k6 run --summary-export /tmp/hc.json test/k6/health-check.js",
            "cat > /tmp/notes.md << 'EOF'",
            "d=$(mktemp -d)",
            "mktemp -d",
            "ls /var/folders/vv/zyprz6nj5nxgg7ddj_cs7wndggztv3/T/opencode",
            "echo hi > $TMPDIR/scratch.txt");

    /**
     * Commands against the sanctioned scratch directory, none of which may resolve to {@code deny}. These are the
     * positive control: they are the ordinary shapes of work in {@code .tmp/}, including the {@code git worktree}
     * recipe {@code AGENTS.md} recommends and the {@code rm} forms the eight existing allow rules exist to permit.
     */
    private static final List<String> MUST_NOT_BE_DENIED = List.of(
            "ls -la .tmp/permcheck/throwaway.txt",
            "rm -f .tmp/permcheck/throwaway.txt",
            "rm -rf .tmp",
            "mkdir -p .tmp/scratch",
            "git worktree add .tmp/scratch HEAD",
            "cat > .tmp/notes.md << 'EOF'");

    /**
     * A floor under the rule count, so that a truncated or restructured configuration file cannot let every rule
     * below pass over an empty map. The map held thirty entries when this test was written.
     */
    private static final int MINIMUM_BASH_RULES = 25;

    @Test
    @DisplayName("declares all six of them, so none can be dropped as apparent noise")
    void shouldDeclareEveryRequiredDenyRule() {
        final Map<String, String> rules = readBashRules();

        final List<String> missing = REQUIRED_DENIES.stream()
                .filter(pattern -> !"deny".equals(rules.get(pattern)))
                .toList();

        assertThat(missing)
                .as("A bash deny rule that keeps scratch work inside the worktree is missing from %s, or no longer "
                        + "reads \"deny\". These six are the only enforcement the .tmp/ convention has — before they "
                        + "existed the rule was prose, and an agent that had read it wrote nine scratch files to "
                        + "/tmp in a single day. Restore the entry rather than relaxing this test, and keep the "
                        + "entries at the end of the map, since the last matching rule wins (Blueprint §7.9). "
                        + "Missing or weakened patterns: %s", CONFIG, missing)
                .isEmpty();
    }

    @Test
    @DisplayName("actually denies the commands that put scratch files outside the worktree")
    void shouldResolveOutsideScratchCommandsToDeny() {
        final Map<String, String> rules = readBashRules();

        final Map<String, String> wrong = new LinkedHashMap<>();
        for (final String command : MUST_BE_DENIED) {
            final String action = resolve(rules, command);
            if (!"deny".equals(action)) {
                wrong.put(command, action);
            }
        }

        assertThat(wrong)
                .as("A command that writes scratch data outside the worktree no longer resolves to deny. Presence of "
                        + "the six patterns is not enough on its own: the last matching rule wins, so a rule appended "
                        + "below them can re-open what they close. Commands and the action they now resolve to: %s",
                        wrong)
                .isEmpty();
    }

    @Test
    @DisplayName("leaves .tmp/ itself alone, which is the half that would hurt most to get wrong")
    void shouldNotDenyTheSanctionedScratchDirectory() {
        final Map<String, String> rules = readBashRules();

        final Map<String, String> denied = new LinkedHashMap<>();
        for (final String command : MUST_NOT_BE_DENIED) {
            final String action = resolve(rules, command);
            if ("deny".equals(action)) {
                denied.put(command, action);
            }
        }

        assertThat(denied)
                .as("A deny rule now catches .tmp/ itself, which forbids the only sanctioned scratch directory and is "
                        + "worse than having no deny at all. The six patterns are safe only because each needs a "
                        + "literal /tmp boundary that a worktree path spells /.tmp — widening one to */tmp* or "
                        + "*tmp* breaks that. Offending commands: %s", denied)
                .isEmpty();
    }

    @Test
    @DisplayName("is reading the ruleset it guards, so none of the rules above can pass over nothing")
    void shouldFindTheRulesetItGuards() {
        final Map<String, String> rules = readBashRules();

        assertThat(rules)
                .as("No bash permission rules were parsed out of %s — is the working directory the project root?",
                        CONFIG)
                .isNotEmpty()
                .hasSizeGreaterThanOrEqualTo(MINIMUM_BASH_RULES);

        assertThat(rules.get("*"))
                .as("The bash ruleset no longer opens with a \"*\" wildcard, which is the entry every rule below it "
                        + "narrows. If the default has genuinely changed, this test and Blueprint §7.8 both need "
                        + "rewriting rather than adjusting.")
                .isEqualTo("allow");
    }

    @Test
    @DisplayName("resolves the two cases observed in the log, which is what validates the matcher mirrored here")
    void shouldReproduceTheObservedResolutions() {
        final Map<String, String> rules = readBashRules();

        assertThat(resolvedPattern(rules, "ls -la /tmp/a.txt"))
                .as("The mirrored matcher no longer reproduces the resolution actually observed on 2026-09-05: "
                        + "pattern=\"ls -la /tmp/a.txt\" action.pattern=*/tmp/* action.action=deny. If the mirror is "
                        + "wrong, the two behavioural rules above are measuring the wrong thing.")
                .isEqualTo("*/tmp/*");

        assertThat(resolvedPattern(rules, "rm -f .tmp/permcheck/throwaway.txt"))
                .as("The mirrored matcher no longer reproduces the observed positive control: "
                        + "pattern=\"rm -f .tmp/permcheck/throwaway.txt\" action.pattern=\"rm -f .tmp/*\" "
                        + "action.action=allow. That resolution is also the evidence that last-matching-rule-wins, "
                        + "since \"rm *\" appears earlier and asks.")
                .isEqualTo("rm -f .tmp/*");
    }

    /**
     * Reads {@code permission.bash} in file order. Order is load-bearing: OpenCode applies the last matching rule, so
     * a map read into an unordered structure would resolve commands differently from the running agent.
     *
     * @return the bash rules, keyed by pattern in the order the file declares them
     */
    private static Map<String, String> readBashRules() {
        final JsonNode root;
        try {
            root = new ObjectMapper().readTree(Files.readString(CONFIG));
        } catch (final IOException e) {
            throw new UncheckedIOException("Could not read " + CONFIG, e);
        }

        final Map<String, String> rules = new LinkedHashMap<>();
        final JsonNode bash = root.path("permission").path("bash");
        final Iterator<Map.Entry<String, JsonNode>> fields = bash.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            rules.put(field.getKey(), field.getValue().asText());
        }
        return rules;
    }

    /**
     * Resolves a command the way OpenCode does — every pattern compiled to an anchored regex with {@code *} standing
     * for any run of characters, and the last matching rule winning.
     *
     * @param rules the bash rules in file order
     * @param command the command text to resolve
     * @return the winning action, or {@code allow} if no rule matches, mirroring the implicit default
     */
    private static String resolve(final Map<String, String> rules, final String command) {
        String action = "allow";
        for (final Map.Entry<String, String> rule : rules.entrySet()) {
            if (matches(rule.getKey(), command)) {
                action = rule.getValue();
            }
        }
        return action;
    }

    /**
     * Resolves a command to the pattern that wins, rather than to its action, so that the mirror can be checked
     * against the {@code action.pattern=} field of a real log line.
     *
     * @param rules the bash rules in file order
     * @param command the command text to resolve
     * @return the last pattern that matches, or {@code null} when none does
     */
    private static String resolvedPattern(final Map<String, String> rules, final String command) {
        String winner = null;
        for (final String pattern : rules.keySet()) {
            if (matches(pattern, command)) {
                winner = pattern;
            }
        }
        return winner;
    }

    /**
     * Tests one glob against one command. Every character is a literal except {@code *}, and the match is anchored at
     * both ends — which is precisely why <code>&#42;/tmp/&#42;</code> cannot catch a worktree path, whose spelling is
     * {@code /.tmp/}.
     *
     * @param pattern the glob from the configuration file
     * @param command the command text
     * @return whether the glob matches the whole command
     */
    private static boolean matches(final String pattern, final String command) {
        final List<String> literals = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '*') {
                literals.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        literals.add(current.toString());

        final StringBuilder regex = new StringBuilder();
        for (int i = 0; i < literals.size(); i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literals.get(i)));
        }
        return Pattern.compile("^" + regex + "$", Pattern.DOTALL)
                .matcher(command)
                .matches();
    }
}
