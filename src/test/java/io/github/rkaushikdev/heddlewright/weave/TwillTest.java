package io.github.rkaushikdev.heddlewright.weave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.heddlewright.draft.Drawdown;
import io.github.rkaushikdev.heddlewright.draft.Threading;
import io.github.rkaushikdev.heddlewright.draft.Tieup;
import io.github.rkaushikdev.heddlewright.draft.Treadling;
import io.github.rkaushikdev.heddlewright.util.DraftException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.util.Runs;
import io.github.rkaushikdev.heddlewright.weave.Twill.Hand;
import io.github.rkaushikdev.heddlewright.weave.Twill.Options;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TwillTest {

    @Nested
    class ParseTwillNotation {

        @Test
        void readsSlashNotationAndSpacedNotationTheSameWay() {
            assertThat(Twill.parseTwillNotation("2/2")).containsExactly(2, 2);
            assertThat(Twill.parseTwillNotation("2 2")).containsExactly(2, 2);
            assertThat(Twill.parseTwillNotation(" 3/1 ")).containsExactly(3, 1);
        }

        @Test
        void readsACompoundTwill() {
            assertThat(Twill.parseTwillNotation("2/1/1/2")).containsExactly(2, 1, 1, 2);
        }

        @Test
        void refusesAnOddNumberOfPartsWhichWouldNotAlternate() {
            assertThatThrownBy(() -> Twill.parseTwillNotation("2/1/1")).hasMessageContaining("alternate up and down");
        }

        @Test
        void refusesASinglePartAndAPartThatIsNotACount() {
            assertThatThrownBy(() -> Twill.parseTwillNotation("2")).isInstanceOf(DraftException.class);
            assertThatThrownBy(() -> Twill.parseTwillNotation("2/x")).hasMessageContaining("is not a count");
            assertThatThrownBy(() -> Twill.parseTwillNotation("2/0")).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void roundTripsThroughFormatTwillNotation() {
            assertThat(Twill.formatTwillNotation(Twill.parseTwillNotation("2/1/1/2")))
                    .isEqualTo("2/1/1/2");
        }
    }

    @Nested
    class TwillPatternAndTwillRepeatSize {

        @Test
        void laysTheCountsOutAsFaceCellsStartingWithTheWarpUp() {
            assertThat(Twill.twillPattern(List.of(2, 2))).containsExactly(1, 1, 0, 0);
            assertThat(Twill.twillPattern(List.of(3, 1))).containsExactly(1, 1, 1, 0);
            assertThat(Twill.twillPattern(List.of(2, 1, 1, 2))).containsExactly(1, 1, 0, 1, 0, 0);
        }

        @Test
        void theRepeatIsTheSumOfTheCounts() {
            assertThat(Twill.twillRepeatSize(List.of(2, 2))).isEqualTo(4);
            assertThat(Twill.twillRepeatSize(List.of(2, 1, 1, 2))).isEqualTo(6);
        }
    }

    @Nested
    class TwillWeave {

        @Test
        void weavesThe22TwillMovingOneEndPerPick() {
            assertThat(Twill.twillWeave("2/2").format()).isEqualTo("XX..\n.XX.\n..XX\nX..X");
        }

        @Test
        void weavesThe31WarpFacedTwill() {
            assertThat(Twill.twillWeave("3/1").format('o', '.')).isEqualTo("ooo.\n.ooo\no.oo\noo.o");
        }

        @Test
        void runsTheLineTheOtherWayForAnSTwill() {
            assertThat(Twill.twillWeave("2/2", Options.DEFAULT.handedness(Hand.S))
                            .format())
                    .isEqualTo("XX..\nX..X\n..XX\n.XX.");
        }

        @Test
        void reclinesTheLineOnALargerStep() {
            assertThat(Twill.twillWeave("2/2", Options.DEFAULT.step(2)).format())
                    .isEqualTo("XX..\n..XX\nXX..\n..XX");
        }

        @Test
        void extendsToAWiderPieceOfClothWithoutChangingTheRepeat() {
            Matrix cloth = Twill.twillWeave("2/2", Options.DEFAULT.size(8, 12));
            assertThat(cloth.dimensions()).isEqualTo(new Matrix.Dimensions(8, 12));
            assertThat(cloth.format().split("\n")[0]).isEqualTo("XX..XX..XX..");
        }

        @Test
        void takesAParsedCountListAsWellAsAString() {
            assertThat(Twill.twillWeave(List.of(2, 2))).isEqualTo(Twill.twillWeave("2/2"));
        }

        @Test
        void floatsExactlyAsFarAsTheNotationSays() {
            Matrix cloth = Twill.twillWeave("3/1", Options.DEFAULT.size(8, 8));
            assertThat(Runs.longestRun(cloth.row(0), 1)).isEqualTo(3);
            assertThat(Runs.longestRun(cloth.row(0), 0)).isEqualTo(1);
        }

        @Test
        void rejectsAHandednessItDoesNotKnow() {
            assertThatThrownBy(() -> Twill.twillWeave("2/2", Options.fromRecord(Json.obj("handedness", "X"))))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class BrokenDraftingOrder {

        @Test
        void runsEverySecondBlockBackwards() {
            assertThat(Twill.brokenDraftingOrder(4, 2)).containsExactly(0, 1, 3, 2);
            assertThat(Twill.brokenDraftingOrder(8, 4)).containsExactly(0, 1, 2, 3, 7, 6, 5, 4);
        }

        @Test
        void isAPermutationSoNoEndIsDraftedTwice() {
            for (int width : List.of(1, 2, 3, 4, 5)) {
                assertThat(Twill.brokenDraftingOrder(8, width).stream().sorted())
                        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
            }
        }

        @Test
        void leavesTheOrderStraightWhenTheBlockCoversEverything() {
            assertThat(Twill.brokenDraftingOrder(4, 4)).containsExactly(0, 1, 2, 3);
        }
    }

    @Nested
    class BrokenTwill {

        @Test
        void breaksTheLineAtTheGivenWidth() {
            assertThat(Twill.brokenTwill("2/2", 2).format()).isEqualTo("XX..\n.X.X\n..XX\nX.X.");
        }

        @Test
        void isThePlainTwillWhenTheBreakCoversTheWholeRepeat() {
            assertThat(Twill.brokenTwill("2/2", 4)).isEqualTo(Twill.twillWeave("2/2"));
        }

        @Test
        void keepsTheInterlacementCountsOfTheTwillItCameFrom() {
            Matrix broken = Twill.brokenTwill("3/1", 2);
            assertThat(Drawdown.warpFaceRatio(broken)).isEqualTo(0.75);
            for (List<Integer> row : broken.allRows()) {
                assertThat(row.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
            }
        }
    }

    @Nested
    class PointedTwill {

        @Test
        void turnsTheLineBackAndMakesAHerringbone() {
            Matrix cloth = Twill.pointedTwill("2/2", 4);
            assertThat(cloth.dimensions()).isEqualTo(new Matrix.Dimensions(4, 6));
            assertThat(cloth.format('o', '.')).isEqualTo("oo...o\n.oo.oo\n..ooo.\no..o..");
        }

        @Test
        void agreesWithWeavingAPointThreadingAgainstATwillTieup() {
            Matrix woven = Drawdown.drawdownFromDraft(Json.obj(
                    "threading", Threading.pointDraw(4),
                    "tieup", Tieup.twillTieup(4, 2),
                    "treadling", Treadling.straightTreadling(4, 4),
                    "shafts", 4,
                    "treadles", 4));
            assertThat(Twill.pointedTwill("2/2", 4)).isEqualTo(woven);
        }

        @Test
        void needsAtLeastTwoEndsBeforeTheTurn() {
            assertThatThrownBy(() -> Twill.pointedTwill("2/2", 1)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class TwillAngle {

        @Test
        void isFortyFiveDegreesOnASquareSettWithAStepOfOne() {
            assertThat(Twill.twillAngle(24, 24)).isEqualTo(45);
        }

        @Test
        void steepensWhenThereAreMoreEndsThanPicks() {
            assertThat(Twill.twillAngle(48, 24)).isEqualTo(63.43);
        }

        @Test
        void reclinesAsTheStepGrows() {
            assertThat(Twill.twillAngle(2, 24, 24)).isEqualTo(26.57);
        }

        @Test
        void needsBothSetts() {
            assertThatThrownBy(() -> Twill.twillAngle(0, 24)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class TwillBalance {

        @Test
        void addsTheUpCountsAndTheDownCountsSeparately() {
            assertThat(Twill.twillBalance("2/2")).isEqualTo(new Twill.Balance(2, 2, 4));
            assertThat(Twill.twillBalance("3/1")).isEqualTo(new Twill.Balance(3, 1, 4));
            assertThat(Twill.twillBalance("2/1/1/2")).isEqualTo(new Twill.Balance(3, 3, 6));
        }

        @Test
        void agreesWithTheFaceRatioOfTheWovenRepeat() {
            Twill.Balance balance = Twill.twillBalance("3/1");
            assertThat(Drawdown.warpFaceRatio(Twill.twillWeave("3/1")))
                    .isEqualTo((double) balance.warp() / balance.repeat());
        }

        @Test
        void callsATwillWarpFacedWhenItShowsMoreWarp() {
            assertThat(Twill.isWarpFaced("3/1")).isTrue();
            assertThat(Twill.isWarpFaced("1/3")).isFalse();
            assertThat(Twill.isWarpFaced("2/2")).isFalse();
        }
    }

    @Nested
    class ReverseTwill {

        @Test
        void swapsEachPairWhichWeavesTheBackOfTheCloth() {
            assertThat(Twill.reverseTwill("3/1")).containsExactly(1, 3);
            assertThat(Twill.reverseTwill("2/1/1/2")).containsExactly(1, 2, 2, 1);
        }

        @Test
        void isItsOwnInverse() {
            assertThat(Twill.reverseTwill(Twill.reverseTwill("3/1"))).containsExactly(3, 1);
        }

        @Test
        void turnsAWarpFacedTwillIntoAWeftFacedOne() {
            assertThat(Twill.isWarpFaced(Twill.reverseTwill("3/1"))).isFalse();
        }
    }

    @Nested
    class Handedness {

        @Test
        void namesTheTwoTwillDirections() {
            assertThat(Hand.HANDEDNESS.stream().map(Hand::toString)).containsExactly("Z", "S");
        }
    }

    @Nested
    class OnAnyDraftingOrder {

        @Test
        void weavesTheStraightTwillOnAStraightOrder() {
            assertThat(Twill.twillOnOrder(List.of(2, 2), List.of(0, 1, 2, 3), 4, Twill.Hand.Z))
                    .isEqualTo(Twill.twillWeave("2/2"));
            assertThat(Twill.twillOnOrder(List.of(3, 1), List.of(0, 1, 2, 3), 4, Twill.Hand.S))
                    .isEqualTo(Twill.twillWeave("3/1", Options.DEFAULT.handedness(Twill.Hand.S)));
        }

        @Test
        void weavesTheBrokenAndPointedTwillsOnTheirOrders() {
            assertThat(Twill.twillOnOrder(List.of(2, 2), Twill.brokenDraftingOrder(4, 2), 4, Twill.Hand.Z))
                    .isEqualTo(Twill.brokenTwill("2/2", 2));
            assertThat(Twill.twillOnOrder(List.of(2, 2), List.of(0, 1, 2, 3, 2, 1), 4, Twill.Hand.Z))
                    .isEqualTo(Twill.pointedTwill("2/2", 4));
        }

        @Test
        void takesAnOrderThatRunsPastTheRepeat() {
            assertThat(Twill.twillOnOrder(List.of(2, 2), List.of(4, 5), 1, Twill.Hand.Z)
                            .format())
                    .isEqualTo("XX");
        }

        @Test
        void refusesAnEmptyOrderANegativeEndOrNoHand() {
            assertThatThrownBy(() -> Twill.twillOnOrder(List.of(2, 2), List.of(), 4, Twill.Hand.Z))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Twill.twillOnOrder(List.of(2, 2), List.of(0, -1), 4, Twill.Hand.Z))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Twill.twillOnOrder(List.of(2, 2), List.of(0, 1), 4, null))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class Herringbone {

        @Test
        void breaksTheLineHalfARepeatAtTheTurn() {
            assertThat(Twill.herringbone("2/2", 4).format()).isEqualTo("XX..XX..\n.XX.X..X\n..XX..XX\nX..X.XX.");
        }

        @Test
        void putsFaceAgainstBackAtTheTurnOnEveryPick() {
            Matrix cloth = Twill.herringbone("2/2", 4);
            for (int p = 0; p < cloth.rows(); p += 1) {
                assertThat(cloth.get(p, 3)).isNotEqualTo(cloth.get(p, 4));
                assertThat(cloth.get(p, 7)).isNotEqualTo(cloth.get(p, 0));
            }
        }

        @Test
        void sharesNoEndAtTheTurnSoTheRepeatIsTwiceTheWidth() {
            assertThat(Twill.herringbone("2/2", 6).cols()).isEqualTo(12);
            assertThat(Twill.pointedTwill("2/2", 6).cols()).isEqualTo(10);
        }

        @Test
        void keepsTheUpAndDownCountsOfTheNotationOnEveryPick() {
            Matrix cloth = Twill.herringbone(List.of(3, 1), 4, 8);
            assertThat(cloth.rows()).isEqualTo(8);
            for (int p = 0; p < cloth.rows(); p += 1) {
                assertThat(cloth.row(p).stream().mapToInt(Integer::intValue).sum())
                        .isEqualTo(6);
            }
        }

        @Test
        void refusesATurnNarrowerThanTwoEnds() {
            assertThatThrownBy(() -> Twill.herringbone("2/2", 1)).isInstanceOf(InvalidArgumentException.class);
        }
    }
}
