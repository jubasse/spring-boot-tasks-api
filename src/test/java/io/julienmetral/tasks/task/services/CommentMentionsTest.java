package io.julienmetral.tasks.task.services;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentMentionsTest {

    private static final UUID ALICE = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final UUID BOB = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a6c");
    private static final UUID CAROL = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a7d");

    private static String token(UUID id) {
        return "<@" + id + ">";
    }

    @Test
    void parseWithoutMentionsIsEmpty() {
        assertThat(CommentMentions.parse("No mention here, just an email a@b.c")).isEmpty();
    }

    @Test
    void parseEmptyBodyIsEmpty() {
        assertThat(CommentMentions.parse("")).isEmpty();
    }

    @Test
    void parseKeepsTheOrderOfFirstAppearance() {
        String body = "%s then %s then %s".formatted(token(CAROL), token(ALICE), token(BOB));

        assertThat(CommentMentions.parse(body)).containsExactly(CAROL, ALICE, BOB);
    }

    @Test
    void parseListsARepeatedMentionOnceAtItsFirstPosition() {
        String body = "%s %s %s %s".formatted(token(BOB), token(ALICE), token(BOB), token(ALICE));

        assertThat(CommentMentions.parse(body)).containsExactly(BOB, ALICE);
    }

    @Test
    void parseTreatsUpperAndLowerCaseIdsAsTheSameUser() {
        String body = "<@" + ALICE.toString().toUpperCase() + "> and " + token(ALICE);

        assertThat(CommentMentions.parse(body)).containsExactly(ALICE);
    }

    @Test
    void parseFindsMentionsGluedToSurroundingText() {
        assertThat(CommentMentions.parse("hey" + token(ALICE) + "!")).containsExactly(ALICE);
    }

    @Test
    void parseIgnoresMalformedTokens() {
        String body = String.join(" ",
                "@" + ALICE,
                "<" + ALICE + ">",
                "<@ " + ALICE + ">",
                "<@" + ALICE,
                "<@" + ALICE.toString().replace("-", "") + ">",
                "<@" + ALICE.toString().substring(1) + ">",
                "<@" + ALICE + "0>",
                "<@0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5g>",
                "<@alice>"
        );

        assertThat(CommentMentions.parse(body)).isEmpty();
    }

    @Test
    void parseFindsValidTokensBetweenMalformedOnes() {
        String body = "<@nope> " + token(BOB) + " <@" + ALICE.toString().substring(1) + ">";

        assertThat(CommentMentions.parse(body)).containsExactly(BOB);
    }

    @Test
    void parseReturnsAnUnmodifiableSet() {
        assertThatThrownBy(() -> CommentMentions.parse(token(ALICE)).add(BOB))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void renderReplacesEachTokenWithTheKnownName() {
        String body = "Thanks %s, can %s review?".formatted(token(ALICE), token(BOB));

        String rendered = CommentMentions.render(body, Map.of(ALICE, "Alice", BOB, "Bob")::get);

        assertThat(rendered).isEqualTo("Thanks @Alice, can @Bob review?");
    }

    @Test
    void renderReplacesEveryOccurrenceOfARepeatedMention() {
        String rendered = CommentMentions.render(token(ALICE) + " " + token(ALICE), Map.of(ALICE, "Alice")::get);

        assertThat(rendered).isEqualTo("@Alice @Alice");
    }

    @Test
    void renderKeepsTheTokenOfAnUnknownUser() {
        String body = "%s and %s".formatted(token(ALICE), token(CAROL));

        String rendered = CommentMentions.render(body, Map.of(ALICE, "Alice")::get);

        assertThat(rendered).isEqualTo("@Alice and " + token(CAROL));
    }

    @Test
    void renderTreatsDollarAndBackslashInNamesLiterally() {
        String body = "%s and %s".formatted(token(ALICE), token(BOB));

        String rendered = CommentMentions.render(body, Map.of(ALICE, "Cash $1 $0", BOB, "C:\\Users\\bob")::get);

        assertThat(rendered).isEqualTo("@Cash $1 $0 and @C:\\Users\\bob");
    }

    @Test
    void renderLooksUpUpperCaseTokensByTheirCanonicalId() {
        String body = "<@" + ALICE.toString().toUpperCase() + ">";

        assertThat(CommentMentions.render(body, Map.of(ALICE, "Alice")::get)).isEqualTo("@Alice");
    }

    @Test
    void renderLeavesMalformedTokensAndPlainTextUntouched() {
        String body = "Cost: $5 <@alice> \\n <@" + ALICE.toString().substring(1) + ">";

        assertThat(CommentMentions.render(body, id -> "Nobody")).isEqualTo(body);
    }
}
