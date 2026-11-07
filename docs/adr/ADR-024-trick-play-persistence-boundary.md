# ADR-024: One Adapter Implements Both Trick-Play Ports, and Neither Port Authorises Anybody

**Status:** Accepted
**Date:** 2026-08-13
**Deciders:** @architecture-guardian, @security-auditor, @tech-lead

## Context

EOP-14 Slice C1 adds storage for dealt hands and played tricks. It is reached through two ports,
`HandRepository` and `TrickRepository`, split that way because dealing and playing are separate
operations with separate callers arriving in Slice C2: `DealHandsUseCase` has no business holding a
method that appends a play, and `PlayCardUseCase` has none holding a method that deals.

Playing a card, however, is not separable in *storage*. One play deletes a row from `hand_card`,
inserts a row into `trick_play`, and inserts one row per component into `trick_play_component`.
Those writes must succeed or fail together. If they do not, a card leaves a hand with no play
recorded — a card that is gone from the game, in a game where every card is dealt and the deal is
the only source of cards (ADR-023, decision 1). There is no compensating action available, because
nothing in the schema records that the card was ever held.

Separately, and this is the reason this ADR exists rather than a comment: gate 3 of Slice C1's
definition-of-done rejected the slice twice for documentation that claimed an authorisation the
layer does not perform. The claim was that because every write begins with a conditional update on
the session row, a caller with no business in the session is refused before any row confirming the
session exists is read. That is false — the conditional statements carry no player identifier — and
it took two remediation rounds to remove it from the adapter's javadoc, the two ports and
`CHANGELOG.md`. The underlying fact needs a home a Slice C2 author will actually find, because
ADR-023's obligation 1 assigns the requester check to the use case while the adapter now throws two
exceptions that look exactly like it.

## Decision

### 1. One class implements both ports

`TrickPlayRepositoryAdapter` implements `HandRepository` and `TrickRepository`. The alternative
decompositions are both worse, and it is worth naming why, because "one adapter per port" is what a
first reading of Clean Architecture pushes toward:

- **Two adapters, two transactions.** This admits the torn state above as a possible outcome. It is
  not a trade-off; it is the defect.
- **Two adapters, one transaction declared above them.** The only place left to declare that
  transaction is the use-case package, which would put `org.springframework.transaction` on the
  inward side of the dependency rule. `.opencode/rules/clean-architecture.md` forbids it, and
  rightly: the use case would then be unable to state its own logic without stating Spring's.

> **Amended 2026-08-13, EOP-14 Slice C2 — the second bullet's premise is false and is retracted.**
> "The only place left to declare that transaction is the use-case package" is wrong, and it is wrong
> in exactly the way [ADR-025](ADR-025-dealing-is-its-own-use-case.md) was wrong about the seam
> between starting a session and dealing. There is another place: a `@Transactional` composer in the
> outer ring. Spring's default `REQUIRED` propagation means two adapter methods that are each
> `@Transactional` *join* an enclosing transaction rather than starting their own, so a composer above
> them demarcates one unit of work while `org.springframework.transaction` stays entirely outside
> `usecase`. Nothing about the dependency rule prevents it, and the mechanism is already demonstrable
> in this codebase: `SessionRepositoryAdapter.recordStarted` (`SessionRepositoryAdapter.java:120-126`)
> and `TrickPlayRepositoryAdapter.recordDeal` (`TrickPlayRepositoryAdapter.java:241-252`) are both
> plain `@Transactional`, so they would compose that way today. The bullet's rhetorical move —
> declaring an option not to exist so that it need not be argued against — is the failure, not merely
> its conclusion.
>
> **The conclusion stands, on the two grounds that survive.** First, an adapter owning its own
> transaction is a legitimate boundary rather than a consolation prize: the transaction is a detail of
> the persistence mechanism, and the ring that owns the mechanism is the right ring to own its atomic
> unit. A composer above the ports would move that decision *outward past* the layer that knows why
> the writes belong together, leaving the reason in one ring and the enforcement in another. Second,
> composition is cost and coupling, not impossibility: the composer must know that these particular
> two calls form one unit and must be kept in step with every future change to that fact, which is a
> second place for the invariant to be stated and therefore a second place for it to drift.
>
> Read the paragraph below beginning "Placing the transaction boundary inside the adapter" with the
> same correction applied. Where it says "Composed in the use case from two adapters", read "composed
> by any caller above the two adapters" — the composer need not, and per this amendment should not,
> sit in `usecase`. That paragraph's argument is unaffected by the substitution: a lock-ordering
> policy spread across a composer and two adapters is spread across three files wherever the composer
> lives, whereas here it is a property of one method. The status of this ADR is unchanged; one
> supporting premise is retracted and the decision is re-justified, not superseded.

The ports stay separate because the *callers* are separate. Only the implementation is shared.
Interface segregation is a property of the interfaces, not of the number of classes behind them.

Exactly two responsibilities live in that class and nowhere else. The first is the transaction
boundary. The second is translating a constraint violation into a domain exception, so that Spring
Data types, JPA entities and `DataIntegrityViolationException` all stop there and what continues
inwards is something the domain and the web layer already understand.

Placing the transaction boundary inside the adapter has a second effect that was not the reason for
the decision but is now a reason to keep it: ADR-023's lock-ordering policy is a statement about the
order of statements *within one transaction*. Composed in the use case from two adapters, that
policy would become a convention spread across three files. Composed here, it is a property of one
method that a reader can check by reading it.

### 2. Neither port authorises anybody, and no implementation of them can

This is the decision most likely to be misread, so it is stated flatly. **No method on either port
takes an acting player.** It follows that no statement either port issues can refuse a caller on
the grounds of *who is asking*. Every refusal these ports make is on the grounds of what the
session's *state* is.

What the adapter does check, method by method:

- `recordDeal` and `appendPlay` assert that the player a row names holds the seat that row claims.
  That is a check on the **row**, not on the **requester**. It happens to throw
  `NotYourSeatException` (403) and `PlayerNotInSessionException` (404), which is exactly what makes
  it mistakable for authorisation.
- `openTrick` and `recordResolution` assert nothing of the kind. Half the write surface has no
  membership check at all, and no foreign key can supply one, because `trick` has no player column
  and `recordResolution` writes only `trick.winner_play_id`.
- `findBySessionId`, `findCurrentLeaderSeat` and `findCurrentTrick` cannot make such a check. There
  is no parameter to check against. For the reads, authorisation here is not merely absent but
  impossible.

`expectedLeaderSeat` is not a substitute. It is a compare-and-set witness, and a stranger who
guesses it correctly passes it — a one-in-six shot in a six-seat game.

**Authorising the requester is the use case's obligation, and it must be discharged before any port
method is called.** ADR-023's obligation 1 owns the requirement; this ADR records that the layer
below it does not and cannot help.

### 3. Reads revalidate through domain factories rather than trusting rows

Every read path funnels its rows through a domain factory that re-runs the invariants, rather than
returning what the database happened to hold:

- `Hands.reconstitute` for a session's hands, which re-runs four cross-seat invariants.
- `Trick.reconstitute` for a trick, whose constructor bounds the leader seat.
- `TrickPlay`'s record constructor for each play.
- `seatRead` for the bare `current_leader_seat` column, which has no factory of its own.
- `winnerAmong` for a recorded winner, which refuses a `winner_play_id` that is not among the
  trick's own plays.

The last of those is not defensive programming for its own sake. Slice B *measured*
`fk_trick_winner_play` accepting a play from a different trick and even from a different session.
A winner silently dropped would reconstitute the trick as unresolved and admit a second resolution
with a different outcome.

## Consequences

**Negative — the class is the largest in the repository and will grow.** At roughly 745 lines it is
held together by the transaction boundary and by nothing else. The standing instruction that follows
is: **a new aggregate gets a new adapter.** Only writes that must commit together with an existing
one belong here. Convenience, shared collaborators and topical similarity are not reasons.

**Negative, and load-bearing for Slice C2 — undischarged, the failure paths are an oracle.** When a
conditional update matches no rows, `sessionMoved` re-reads the session and answers with one of five
distinguishable exceptions. To a caller who has only *guessed* a session identifier, those five
answers reveal that the session exists, what status it is in, whether hands have been dealt, whether
they have *not* yet been dealt, and which seat currently leads. Every one of those is the right answer
to give a member and the wrong answer to give a stranger, and only the layer above can tell the two
apart. Slice C2's review must
confirm that each of `DealHandsUseCase`, `PlayCardUseCase` and `ResolveTrickUseCase` authorises the
requester and does so *first*. Two facts make this worth a review item rather than a note: the ports
cannot enforce it, and **no test in Slice C1 fails for its absence**. The build stays green either
way, which is precisely why the obligation is recorded in the decision log rather than left in the
javadoc of the class it constrains.

> **Amended 2026-08-13, EOP-14 Slice C2 — this obligation is now discharged.** The bolded sentence
> above says "undischarged" and was true when it was written; it is no longer. All three use cases
> call `ResolvePlayerUseCase.execute` as their first port call, before any hand, trick or card is
> read: `DealHandsService.java:95` (anchor: `resolvePlayerPort`), `PlayCardService.java:178` and
> `ResolveTrickService.java:158`.
> `PlayCardUseCase` then derives the acting seat and the acting player identifier from the resolved
> player and from nothing the caller supplied (`PlayCardService.java:179-191`), which is stronger than
> the obligation asked for: `PlayCardCommand` has no seat and no player component, so a caller-supplied
> seat is inexpressible rather than rejected. Each of the three has a test asserting a stranger is
> refused *before* any port read happens, asserted against the repository double's read log rather than
> against the status code, because a refusal that has already read is a refusal that has already leaked
> timing. What has **not** changed is the sentence's premise: `sessionMoved` still answers five
> distinguishable exceptions and the ports still take no acting player, so the oracle is closed by the
> layer above and not by the adapter, and it reopens the day a caller reaches these ports through any
> path that does not authorise first. This amendment is written here, at the claim, rather than only in
> the index, because a reader who stops at the word "undischarged" never follows a pointer. See
> [ADR-025](ADR-025-dealing-is-its-own-use-case.md), decision 4. The status of this ADR is unchanged:
> nothing in the decision is superseded, only its implementation state.
>
> *(Anchors re-derived 2026-08-21, EOP-49. The discharge above is unchanged and still holds; only its
> pointers moved. All four line numbers in it had drifted, and drifted in the way that matters most
> for a security obligation: `:120`, `:139` and `:111` had come to land on a `@throws` javadoc tag, a
> `@throws` javadoc tag and a `private final Clock clock;` field respectively, well above the
> statements they were meant to identify — so an auditor following one of them to confirm that
> authorisation precedes any read arrived at documentation, or at a field, and could confirm nothing.
> Only the `PlayCardUseCase` anchor had been reported; the other two were found while re-deriving it.
> The ordinal in the sentence above is also corrected, from "first statement" to "first port call":
> in `PlayCardUseCase` a null check on the command and the read of its session identifier precede the
> call, so "first statement" was false there, while "first port call" is true of all three — and of
> every other use case that authorises the same way, `ReadOwnHandUseCase` and `GetTrickStateUseCase`
> among them. The security property this sentence exists to assert was never in doubt, because
> "before any hand, trick or card is read" was true throughout. Regenerate rather than trust these
> four numbers: `grep -n 'resolvePlayerUseCase\.' src/main/java/org/maglez/eop/usecase/*.java` prints
> every authorisation call site in the layer, one line each.)*

**Negative — one exception ships with no thrower.** `WinningPlayNotInTrickException` is declared and
mapped to 422 but nothing in production raises it. It is Slice C2's `ResolveTrickUseCase` contract
arriving early with the rest of the error vocabulary. Per ADR-023's obligation 3 it will never have
a storage backstop, so the use-case check is the only check that will ever exist. The adapter's own
detection of a corrupt `winner_play_id` deliberately does *not* use it: that is our data being
wrong, and a 422 would tell a caller their well-formed request was unprocessable.

> **Amended 2026-08-13, EOP-14 Slice C2 — the thrower now exists.** `ResolveTrickService.java:131-135`
> raises `WinningPlayNotInTrickException` (the `throw` itself is `:134`) when the resolved winning play
> is not among the plays of the
> trick being resolved, so the paragraph's "nothing in production raises it" no longer holds. What holds
> is the reason it was written: the check is unreachable through `Trick.resolved()` today, because
> `Trick`'s own constructor already refuses a winner that is foreign to the trick, and it is written
> anyway because `fk_trick_winner_play` confines a winning play to the `trick_play` table and to nothing
> narrower — the composite key that would confine it to *this* trick cannot be expressed in Liquibase,
> and ADR-023's obligation 3 records that it never will be. So this is a guard against our own future
> defect rather than against a caller, which is why it is written at the only layer that can see both
> the trick and its plays.

**Neutral — the read side is whole-or-nothing by design.** `Hands.reconstitute` re-runs the
invariant that one card cannot sit in two hands of the same session, and **no database constraint
stands behind it**: `pk_hand_card` stops a duplicate only within one hand, and
`uq_trick_play_trick_card` stops only a double play into one trick. A per-seat read would satisfy
the invariant vacuously and thereby switch off its only enforcement, so `findBySessionId` returns
the whole session's hands or nothing. The write side stays a one-seat delta, because removing a card
from a hand cannot create a duplicate.

**Neutral — the seat foreign keys are a backstop and not a redundant second layer.** Both
`fk_hand_player_seat` and `fk_trick_play_player_seat` bind `(player_id, seat_order)` to
`player (id, seat_order)`, and neither binds that player row to the *hand's* session. A hand naming
a player from a different session, at a seat that player legitimately holds there, satisfies both
keys. The adapter's session-scoped seat map is the only thing that refuses it. Defence in depth is
real here but asymmetric, and the two layers must not be described as equivalent.

**Positive — constraint translation is proved against the real indexes, which is the only way it
can be proved.** The adapter matches on constraint *name*, because every unique, foreign-key and
check violation arrives as the same Spring type. A stubbed test therefore proves only that the
adapter can read a string it was handed. A constraint renamed by a later migration would pass such a
test and answer 500 in production. `TrickPlayRepositoryAdapterIntegrationTest` drives each violation
against a live schema, which is why the translation is trustworthy and why that test is not
substitutable by unit tests.

## Related

- [ADR-023](ADR-023-deal-remainder-and-turn-order.md) — the deal, the leader seat, the lock-ordering
  tree, and the four Slice C obligations this ADR reports against, including obligation 1's
  requester check and the supersession of the two seat-foreign-key translation rows.
- [ADR-020](ADR-020-session-concurrency-control.md) — the compare-and-set protocol that every write
  in this layer opens with, and the reason `@Version` is mapped but not enforced.
- [ADR-013](ADR-013-feature-flags.md) — `eop.features.trick-play`, which Slice C1 does not ship and
  Slice C2 may not merge without.
- [ADR-005](ADR-005-error-handling-strategy.md) — the problem-detail shape every exception named here
  is answered in.
