# ADR-028: The End of a Hand Is Reported, Not Released and Not Scored

**Status:** Accepted
**Date:** 2026-08-14
**Deciders:** @tech-lead, @architecture-guardian

> **Amended 2026-08-17 (EOP-70, [ADR-040](ADR-040-trick-play-flag-on.md)).**
> All three predecessors of the flag-on story are now discharged: EOP-48 (commit `34d30d7`),
> EOP-15 (ADR-032), and ADR-026 (option 4 chosen — audit logging at the HTTP boundary in
> `TrickController`; see ADR-026 and ADR-040 for the full record). `eop.features.trick-play`
> is now `true` in `application.yml`. The flag and its guards remain until confirmed stable.

> **Amended 2026-08-15 (EOP-15 Slice C, [ADR-032](ADR-032-end-of-game-transitions.md)).**
> The second decision below — "EOP-14 does not transition a session to `COMPLETED`" — is
> superseded for the **automatic path**: `ResolveTrickUseCase` now calls
> `SessionRepository.recordCompleted` when `nextLeaderSeat` is empty, so the last trick
> resolving does transition the session to `COMPLETED`. The facilitator may also end early
> via `POST /api/v1/sessions/{sessionId}/end`. Both paths are gated on
> `eop.features.trick-play`. The consequence that "a client watching only
> `GET /api/v1/sessions/{sessionId}` will never see a session finish" is therefore no
> longer true once the flag is on. The first decision (flag stays `false`) and the
> reasoning about ADR-026 and EOP-48 as predecessors of the flag-on story are unaffected.

## Context

EOP-14 Slice E completed the trick-play mechanics. The whole deck is dealt, cards are
played in turn, a trick is resolved, the lead passes to the winner if the winner still
holds a card and clockwise to the next seat that does if it does not, and when no seat
holds a card the session records that no seat leads at all. A new read,
`GET /api/v1/sessions/{sessionId}/tricks/current`, publishes the three answers a client
cannot derive from a trick on its own — which seat may play, whether the trick is
complete, and which seat leads next — together with a `handComplete` flag. The three
event names minted but never emitted in earlier slices (`hand-dealt`, `card-played`,
`trick-resolved`) are now published.

That leaves two questions Slice E had to answer and neither of which the code answers
by itself, because in both cases the tempting action is the wrong one and nothing in the
build would have objected to it.

The first is whether Slice E should turn `eop.features.trick-play` on. Everything the
five slices built is behind that one flag, and with it `false` the five routes do not
exist and the five use cases behind them — five of the twelve the application declares —
are not registered as beans.

> **Amended 2026-08-15 (EOP-15 slice B, [ADR-031](ADR-031-the-score-is-read-through-its-own-route.md)).**
> Those three figures were true when this ADR was written and are not now. The flag withholds
> **six** use-case beans and **two** controllers — eight beans in all — and **six** routes, the sixth
> being `GET /{sessionId}/score`; and the application declares **thirteen** use cases, not twelve.
> The argument below is unaffected: it is about whether to flip the flag, not about how much sits
> behind it, and the answer is still no.
>
> **Amended 2026-08-15 (EOP-15 Slice C, [ADR-032](ADR-032-end-of-game-transitions.md)).**
> The figures above were updated again by Slice C. The flag now withholds **seven** use-case beans
> and **three** controllers — **ten** beans in all — and **seven** routes, the seventh being
> `POST /{sessionId}/end`; and the application declares **fourteen** use cases, not thirteen.
> The argument below remains unaffected.

It is tempting to finish the feature by releasing it in the same pull request that completes it.
Against that stands a condition @security-auditor attached to its approval of Slice D:
dealing, playing and resolving are the product's first competitively meaningful writes,
they are now reachable over HTTP, and no use case logs anything. ADR-026 holds that gap
open deliberately and is still `Proposed` — what is unresolved there is *where* the
logging lives, not whether it is needed, because an SLF4J logger inside
`org.maglez.eop.usecase` would breach the dependency rule that `config/UseCaseConfiguration`
exists to protect. EOP-48 is a second predecessor: `SessionController`'s
`@ConditionalOnProperty` omits `havingValue`, so `session-lifecycle: off` in YAML
silently *enables* the session routes. A flag mechanism that fails open on its sibling
is not a mechanism anybody should rely on for a first release.
*(Amended 2026-08-14, EOP-48 — the description of the defect is kept in the present tense
because it records the context on the day this ADR was accepted, but **it is no longer true of
the code**: commit `34d30d7` added `havingValue = "true"` to `SessionController.java:71` (anchor: `havingValue`) and
gated the four use-case beans that open or mutate a session
(`ApplicationConfiguration.java:122` (anchor: `session-lifecycle`), `:145`, `:189`, `:204`) on the same flag, with a 12-test
off-value suite pinning the `off` spelling. The unconditional beans turned out to be the deeper
half of it: withholding the request mapping alone left an application that could still execute
session creation. This predecessor is therefore **discharged**, and the reasoning that made it
one — a flag mechanism that fails open on its sibling is not one to rely on — survives intact as
the reason it had to be discharged first. [ADR-013](ADR-013-feature-flags.md) carries the
mechanism, the repository-wide mandate it produced and the register entry; **two** of this ADR's
three predecessors remain, ADR-026 and EOP-15.)*

The second is what the session's status should become when the last card is played.
`SessionStatus.COMPLETED` is documented in the entity as "Every trick has been played
*and the score is final*". Scoring is EOP-15's, and EOP-14 computes no score at all.
Moving a session to `COMPLETED` at the end of the last trick would satisfy the first
half of that sentence and falsify the second, and because no code reads the status for
scoring yet, nothing would fail.

## Decision

**Slice E merges with `eop.features.trick-play` still `false`, and turning it on becomes
its own story whose predecessors are ADR-026, EOP-48 and EOP-15.**

The flag's default stays `false` in `src/main/resources/application.yml`. Moving it is a
reviewed change in its own right, which is what `.opencode/rules/feature-flags.md`
already requires of any flag flip outside a developer's own machine. Merging behind a
false flag is safe on its own terms: the routes are unreachable, the beans are absent,
and `TrickPlayDisabledIntegrationTest` asserts both halves rather than only the
404s. The auditor's condition was about *exposure*, and merging does not expose
anything.

**EOP-14 does not transition a session to `COMPLETED`.** The end of a hand is reported,
not recorded as a status. It is reported in two places and derived in both, never
stored: `current_leader_seat` becomes `NULL`, and the state-of-play read publishes
`handComplete`, computed from `Hands.allEmpty()`. `HandCompleteException` — the new 409
that refuses a play into a spent hand — says nothing about the score either. EOP-15 owns
the transition to `COMPLETED`, because EOP-15 is what makes the second half of that
sentence true.

**EOP-15 is therefore the third predecessor of the flag-on story, not merely a
consequence of this one.** The two are the same fact read twice: a hand that ends without
a status and without a score is a coherent thing to *merge* and an incoherent thing to
*expose*. With the flag on and EOP-15 unlanded, a player could be dealt to, could play
every card and could see `handComplete` go true, and the session they were playing would
still report `IN_PROGRESS` for ever with no score anywhere — a game that can be started
and played but never finished. Naming EOP-15 as a predecessor is the same judgement
already applied to ADR-026 and EOP-48: the flag holds until the thing behind it is
defensible to a real player, not merely until it is mechanically complete. It is recorded
in `application.yml`'s flag comment alongside the other two so the predecessor list is
not only in this file.

## Consequences

A client can tell that a hand is over, from `handComplete` on the state-of-play read and
from a 409 titled "Hand complete" if it tries to play anyway. It cannot tell from the
session status, which stays `IN_PROGRESS` after the last card is played. That is an
honest gap rather than a hidden one, but it is a gap: a client written today that
watches only `GET /api/v1/sessions/{sessionId}` will never see a session finish, and it
will need changing when EOP-15 lands. The alternative was a status that named a
condition — a final score — which does not exist. No real client is exposed to the gap,
because EOP-15 is a predecessor of the flag-on story: the gap is visible to this
repository's test suite and to nothing else.

`ABANDONED` remains reachable by no code, as before this slice.

The feature is complete and unreleased. Everything from five slices sits on `main`
unexercised, so the first time it runs in anger will be after a separate flag-flip
story, and the defects that only appear under real use will surface then rather than
now. Merging it later would not have avoided that; it would only have moved the same
delay in front of the code review. What the delay does cost is that the trick-play code
is now the largest body of work in the repository whose only exercise is its own test
suite, and the suite is written by the same hands as the code.

`current_leader_seat = NULL` now carries two meanings — never dealt, and played out —
and only the presence of `hand` rows tells them apart. `claimDeal`'s
`current_leader_seat IS NULL` predicate is therefore no longer the whole deal-once gate;
after a played-out hand the claim succeeds and `uq_hand_session_seat` refuses the second
deal one statement later, inside the same transaction, as the same
`HandAlreadyDealtException`. The caller cannot tell the difference, but a reader of the
SQL can be misled, which is why ADR-020 was amended rather than left to be rediscovered.
`sessionMoved` gained a sixth answer and one extra read to distinguish the two states.
Nothing in the product reaches that branch today.

Two ADRs are now discharged that were previously outstanding: ADR-023's deferral of
end-of-hand recognition to Slice E, and both of ADR-025's — decision 8's silent stream as
well as the `nextLeaderSeat` placeholder its consequences pinned to one line. Both are
amended in place, on this date, so a reader of either page is told they are closed rather
than left to infer it from this one. ADR-026 is *not* discharged and is now a named
predecessor of a story rather than a loose obligation, which is a better place for it than
a paragraph in a slice plan; EOP-48 and EOP-15 sit beside it on that list.

## Related

- [ADR-013](ADR-013-feature-flags.md) — `@ConditionalOnProperty` as the flag
  mechanism, and why the off position is tested.
- [ADR-014](ADR-014-realtime-transport.md) — state-free events and recovery by re-reading,
  which is why the three new event names carry no payload.
- [ADR-020](ADR-020-session-concurrency-control.md) — amended by this slice: the null
  leader seat's second meaning and `sessionMoved`'s sixth answer.
- [ADR-023](ADR-023-deal-remainder-and-turn-order.md) — the uneven deal, the lead passing
  past a card-less winner, and the exception-origin count this slice took from nine to ten.
- [ADR-025](ADR-025-dealing-is-its-own-use-case.md) — the two things left out of Slice C2,
  both now closed.
- [ADR-026](ADR-026-use-case-observability.md) — still `Proposed`, and a predecessor of the
  story that turns the flag on.
- [ADR-027](ADR-027-singleton-subresource-naming.md) — why the new read is
  `/tricks/current` and not `/tricks`.
