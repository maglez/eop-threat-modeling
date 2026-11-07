package org.maglez.eop.inbound.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.maglez.eop.domain.ScoreSheet;

/**
 * The whole score of one session: the Score Card's rows and the standings derived from them.
 *
 * <p>This is every player's rows in one response, which the hand route is forbidden to be. The
 * difference is what is in them. A score names only cards that have already been played, so it
 * discloses nothing the table cannot already see; a hand names cards its holder alone is entitled to
 * see, which is why there is one hand route and it only ever shows the caller's own (ADR-027).
 *
 * <p>Both lists are always present. Before any card is played {@code rows} is empty and every
 * standing sits on nothing, which is the score of a game that has not started rather than a missing
 * answer.
 *
 * <p>There is deliberately no winner field. The winner is whoever holds position 1, and if that
 * position is shared then the game is tied — a fact the standings already carry, and one a second
 * field could only contradict. {@code ScoreSheet} does answer {@code leaders()} and
 * {@code leadIsShared()}, and both are left in the domain for the same reason: they are views of the
 * standings, and publishing a derived view alongside what it derives from invites the two to
 * disagree.
 *
 * @param rows      one row per card played, in trick order and, within a trick, in the order played
 * @param standings one standing per seated player, ordered by position and then by seat
 */
@Schema(name = "ScoreSheet", description = "The score of a session: the Score Card's rows and the standings derived from them")
public record ScoreSheetDto(List<ScoredPlayDto> rows, List<StandingDto> standings) {

    /**
     * Copies both lists defensively so the sheet cannot be altered after construction.
     *
     * @throws NullPointerException if either list is null
     */
    public ScoreSheetDto {
        rows = List.copyOf(rows);
        standings = List.copyOf(standings);
    }

    /**
     * Converts a score sheet into its wire form.
     *
     * @param sheet the score sheet
     * @return the corresponding response body
     */
    public static ScoreSheetDto from(final ScoreSheet sheet) {
        return new ScoreSheetDto(
                sheet.rows().stream().map(ScoredPlayDto::from).toList(),
                sheet.standings().stream().map(StandingDto::from).toList());
    }
}
