package io.github.rkaushikdev.heddlewright.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.loom.BoxMotion.Allotment;
import io.github.rkaushikdev.heddlewright.loom.BoxMotion.Boxing;
import io.github.rkaushikdev.heddlewright.loom.BoxMotion.WeftRun;
import io.github.rkaushikdev.heddlewright.util.CapacityException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BoxMotionTest {

    /** A two and two check, the pattern every box loom is set up on first. */
    private static final List<String> CHECK = List.of("red", "red", "blue", "blue");

    /** Four picks of ground to two of each colour, the way a shot stripe runs. */
    private static final List<String> STRIPE = List.of(
            "ground", "ground", "ground", "ground", "blue", "blue", "ground", "ground", "ground", "ground", "gold",
            "gold");

    private static List<Allotment> fourBoxes() {
        return BoxMotion.allotBoxes(STRIPE, 4);
    }

    @Nested
    class WeftRuns {

        @Test
        void readsThePatternIntoRunsOfOneColourNumberedFromOne() {
            assertThat(BoxMotion.weftRuns(CHECK)).containsExactly(new WeftRun("red", 1, 2), new WeftRun("blue", 3, 2));
        }

        @Test
        void doesNotJoinTheLastRunToTheFirst() {
            // The chain is cut and laced as written, so a weaver reads it straight through even
            // though the cloth repeats.
            assertThat(BoxMotion.weftRuns(List.of("a", "b", "a"))).hasSize(3);
        }

        @Test
        void refusesAnEmptyPatternAndOneWithSomethingThatIsNotAColour() {
            assertThatThrownBy(() -> BoxMotion.weftRuns(List.of())).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> BoxMotion.weftRuns(List.of("red", 2))).hasMessageContaining("pattern[1]");
            assertThatThrownBy(() -> BoxMotion.weftRuns(List.of("red", "")))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class ColoursIn {

        @Test
        void listsEachWeftOnceInTheOrderItIsFirstPicked() {
            assertThat(BoxMotion.coloursIn(STRIPE)).containsExactly("ground", "blue", "gold");
        }
    }

    @Nested
    class AllotBoxes {

        @Test
        void putsTheCommonestWeftInBoxOneWhichIsTheBoxAtRest() {
            assertThat(fourBoxes())
                    .containsExactly(
                            new Allotment("ground", 1, 8), new Allotment("blue", 2, 2), new Allotment("gold", 3, 2));
        }

        @Test
        void breaksATieOnWhichColourIsPickedFirstSoItIsStable() {
            List<Allotment> twice = BoxMotion.allotBoxes(CHECK, 2);
            assertThat(twice).extracting(Allotment::colour).containsExactly("red", "blue");
            assertThat(BoxMotion.allotBoxes(CHECK, 2)).isEqualTo(twice);
        }

        @Test
        void refusesMoreWeftsThanTheLoomHasBoxes() {
            assertThatThrownBy(() -> BoxMotion.allotBoxes(STRIPE, 2)).isInstanceOf(CapacityException.class);
            assertThatThrownBy(() -> BoxMotion.allotBoxes(STRIPE, 2))
                    .hasMessageContaining("3 wefts will not go in 2 boxes");
        }

        @Test
        void needsABoxCount() {
            // An int cannot be left out, so a loom with no boxes at all stands for a count nobody gave.
            assertThatThrownBy(() -> BoxMotion.allotBoxes(CHECK, 0)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class BoxChain {

        @Test
        void givesOneBoxNumberPerPick() {
            assertThat(BoxMotion.boxChain(STRIPE, fourBoxes())).containsExactly(1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 3, 3);
        }

        @Test
        void namesThePickThatWantsAColourWithNoBox() {
            assertThatThrownBy(() -> BoxMotion.boxChain(List.of("ground", "silver"), fourBoxes()))
                    .hasMessageContaining("pick 2 wants silver");
        }

        @Test
        void refusesAnEmptyAllotment() {
            assertThatThrownBy(() -> BoxMotion.boxChain(STRIPE, List.of()))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class BoxChanges {

        @Test
        void countsTheLiftsOverOneRepeatJoiningTheEndBackToTheStart() {
            // 1 1 1 1 2 2 1 1 1 1 3 3 changes at picks 5, 7, 11 and again on the repeat.
            assertThat(BoxMotion.boxChanges(BoxMotion.boxChain(STRIPE, fourBoxes())))
                    .isEqualTo(4);
        }

        @Test
        void countsOneFewerWhenTheChainIsReadStraightThrough() {
            assertThat(BoxMotion.boxChanges(List.of(1, 1, 2, 2), false)).isEqualTo(1);
            assertThat(BoxMotion.boxChanges(List.of(1, 1, 2, 2))).isEqualTo(2);
        }

        @Test
        void isNothingAtAllForOneWeft() {
            assertThat(BoxMotion.boxChanges(List.of(1, 1, 1, 1))).isEqualTo(0);
            assertThat(BoxMotion.boxChanges(List.of(2))).isEqualTo(0);
        }
    }

    @Nested
    class LongestLift {

        @Test
        void isHowFarTheBoxTravelsInTheWorstChange() {
            assertThat(BoxMotion.longestLift(List.of(1, 1, 4, 4))).isEqualTo(3);
        }

        @Test
        void countsTheChangeBackRoundTheRepeatToo() {
            assertThat(BoxMotion.longestLift(List.of(1, 2, 2, 4))).isEqualTo(3);
            assertThat(BoxMotion.longestLift(List.of(1, 2, 2, 4), false)).isEqualTo(2);
        }
    }

    @Nested
    class TheShuttleHasToComeBack {

        @Test
        void letsAnythingThroughOnALoomBoxedAtBothEnds() {
            assertThat(BoxMotion.shuttlesReturn(List.of("a", "b", "a"), Boxing.BOTH))
                    .isTrue();
            assertThat(Boxing.BOXING).extracting(Object::toString).containsExactly("one", "both");
        }

        @Test
        void wantsPairsOnALoomBoxedAtOneEnd() {
            assertThat(BoxMotion.shuttlesReturn(CHECK, Boxing.ONE)).isTrue();
            assertThat(BoxMotion.shuttlesReturn(List.of("a", "b", "b"), Boxing.ONE))
                    .isFalse();
        }

        @Test
        void namesEveryOddRunRatherThanOnlyTheFirst() {
            assertThat(BoxMotion.oddRuns(List.of("a", "b", "b", "c")))
                    .containsExactly(new WeftRun("a", 1, 1), new WeftRun("c", 4, 1));
        }

        @Test
        void refusesThePatternAndSaysWhichRunsAreAtFault() {
            assertThatThrownBy(() -> BoxMotion.assertShuttlesReturn(List.of("a", "b", "b"), Boxing.ONE))
                    .isInstanceOf(CapacityException.class);
            CapacityException error = catchThrowableOfType(
                    CapacityException.class, () -> BoxMotion.assertShuttlesReturn(List.of("a", "b", "b"), Boxing.ONE));
            assertThat(error.detail().get("runs")).isEqualTo(Json.arr(Json.obj("colour", "a", "start", 1, "picks", 1)));
        }

        @Test
        void handsThePatternBackWhenItIsWeavable() {
            assertThat(BoxMotion.assertShuttlesReturn(CHECK, Boxing.ONE)).isSameAs(CHECK);
        }

        @Test
        void refusesABoxingNobodyBuilds() {
            assertThatThrownBy(() -> BoxMotion.shuttlesReturn(CHECK, Boxing.parse("three")))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class WhatChangingCosts {

        private final List<Integer> chain = BoxMotion.boxChain(STRIPE, fourBoxes());

        @Test
        void isAFractionOfAPickForEachLift() {
            assertThat(BoxMotion.picksLostToChanging(chain)).isEqualTo(0.4);
            assertThat(BoxMotion.picksLostToChanging(chain, 0.25)).isEqualTo(1);
        }

        @Test
        void costsNothingWhenTheMotionIsSetRightAndNothingWhenItNeverLifts() {
            assertThat(BoxMotion.picksLostToChanging(chain, 0)).isEqualTo(0);
            assertThat(BoxMotion.picksLostToChanging(List.of(1, 1, 1))).isEqualTo(0);
        }

        @Test
        void refusesANegativeCost() {
            assertThatThrownBy(() -> BoxMotion.picksLostToChanging(chain, -1))
                    .isInstanceOf(InvalidArgumentException.class);
            // A cost that is not a number has no double form; NaN stands in for it.
            assertThatThrownBy(() -> BoxMotion.picksLostToChanging(chain, Double.NaN))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void slowsTheLoomInProportionToHowMuchOfTheRepeatIsLost() {
            // Twelve picks plus four tenths of a pick of lifting, at 180 quoted.
            assertThat(BoxMotion.speedWithChanging(chain, 180)).isCloseTo((180.0 * 12) / 12.4, within(0.05));
        }

        @Test
        void leavesAOneWeftChainAtTheQuotedSpeed() {
            assertThat(BoxMotion.speedWithChanging(List.of(1, 1, 1, 1), 200)).isEqualTo(200);
        }

        @Test
        void needsASpeedToWorkFrom() {
            // A double cannot be left out, so NaN stands in for a speed nobody gave.
            assertThatThrownBy(() -> BoxMotion.speedWithChanging(chain, Double.NaN))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class PickShare {

        @Test
        void saysHowMuchOfTheRepeatEachBoxCarries() {
            assertThat(BoxMotion.pickShare(fourBoxes()))
                    .containsExactly(
                            Json.obj("box", "box 1", "count", 1, "picks", 8, "share", 0.6667),
                            Json.obj("box", "box 2", "count", 1, "picks", 2, "share", 0.1667),
                            Json.obj("box", "box 3", "count", 1, "picks", 2, "share", 0.1667));
        }

        @Test
        void addsUpToTheWholeRepeat() {
            List<JsonObject> share = BoxMotion.pickShare(fourBoxes());
            double total = share.stream()
                    .mapToDouble(row -> row.get("share").asDouble())
                    .sum();
            assertThat(total).isCloseTo(1, within(0.0005));
        }
    }

    @Nested
    class BoxMotionPlan {

        @Test
        void gaitsAPatternUpOnALoomBoxedAtOneEnd() {
            BoxPlan plan = BoxPlan.boxMotionPlan(Json.obj("pattern", STRIPE, "boxes", 4, "boxedAt", "one"));
            assertThat(plan.picks()).isEqualTo(12);
            assertThat(plan.colours()).isEqualTo(3);
            assertThat(plan.chain()).containsExactly(1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 3, 3);
            assertThat(plan.changes()).isEqualTo(4);
            assertThat(plan.longestLift()).isEqualTo(2);
        }

        @Test
        void leavesTheSpeedOutUntilItIsGivenOne() {
            BoxPlan plan = BoxPlan.boxMotionPlan(Json.obj("pattern", STRIPE, "boxes", 4));
            assertThat(plan.picksMinute()).isEmpty();
            assertThat(plan.picksLost()).isEmpty();
            assertThat(BoxPlan.boxMotionPlan(Json.obj("pattern", STRIPE, "boxes", 4, "picksMinute", 180))
                            .picksMinute())
                    .hasValue(174.2);
        }

        @Test
        void refusesAPatternTheBoxingCannotPick() {
            List<String> single = List.of("red", "blue", "blue");
            assertThatThrownBy(() -> BoxPlan.boxMotionPlan(Json.obj("pattern", single, "boxes", 2, "boxedAt", "one")))
                    .isInstanceOf(CapacityException.class);
            assertThatCode(() -> BoxPlan.boxMotionPlan(Json.obj("pattern", single, "boxes", 2, "boxedAt", "both")))
                    .doesNotThrowAnyException();
        }

        @Test
        void refusesAnOptionNobodyDeclaredRatherThanIgnoringIt() {
            assertThatThrownBy(() -> BoxPlan.boxMotionPlan(Json.obj("pattern", CHECK, "boxes", 2, "speed", 180)))
                    .hasMessageContaining("does not take");
        }
    }

    @Nested
    class PatternFromChain {

        @Test
        void readsALacedChainBackIntoTheOrderItPicks() {
            List<Allotment> allotment = fourBoxes();
            assertThat(BoxMotion.patternFromChain(BoxMotion.boxChain(STRIPE, allotment), allotment))
                    .isEqualTo(STRIPE);
        }

        @Test
        void namesABoxTheLoomHasNothingIn() {
            assertThatThrownBy(() -> BoxMotion.patternFromChain(List.of(1, 4), fourBoxes()))
                    .hasMessageContaining("names box 4, which is empty");
        }

        @Test
        void refusesAChainEntryThatIsNotABoxNumber() {
            assertThatThrownBy(() -> BoxMotion.patternFromChain(List.of(0), fourBoxes()))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> BoxMotion.patternFromChain(List.of(), fourBoxes()))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class Travel {

        @Test
        void plansForTravelWhenTheRequestAsks() {
            List<String> order = List.of(
                    "red", "red", "red", "red", "red", "red", "gold", "green", "gold", "green", "gold", "gold", "red",
                    "red", "blue", "blue", "blue", "red");
            BoxPlan byCount = BoxPlan.boxMotionPlan(Json.obj("pattern", order, "boxes", 4));
            BoxPlan byTravel = BoxPlan.boxMotionPlan(Json.obj("pattern", order, "boxes", 4, "allot", "travel"));
            assertThat(byCount.allot()).isEqualTo(BoxPlan.Allot.COUNT);
            assertThat(byCount.travel()).isEqualTo(14);
            assertThat(byTravel.travel()).isEqualTo(10);
            assertThat(byTravel.allotment().get(1).colour()).isEqualTo("blue");
            assertThat(BoxPlan.boxMotionPlan(
                                    order, 4, Boxing.BOTH, java.util.OptionalDouble.of(200), BoxPlan.Allot.TRAVEL)
                            .picksMinute())
                    .isPresent();
            assertThatThrownBy(() -> BoxPlan.boxMotionPlan(Json.obj("pattern", order, "boxes", 4, "allot", "x")))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        private final List<String> stripes = List.of(
                "red", "red", "red", "red", "red", "red", "gold", "green", "gold", "green", "gold", "gold", "red",
                "red", "blue", "blue", "blue", "red");

        @Test
        void addsUpEveryPositionTheBoxMovesRoundTheRepeat() {
            assertThat(BoxMotion.boxTravel(List.of(1, 1, 3, 2))).isEqualTo(2 + 1 + 1);
            assertThat(BoxMotion.boxTravel(List.of(2, 2, 2))).isEqualTo(0);
            assertThat(BoxMotion.boxTravel(List.of(4))).isEqualTo(0);
        }

        @Test
        void putsTwoColoursThatAlternateInNeighbouringBoxes() {
            List<BoxMotion.Allotment> byCount = BoxMotion.allotBoxes(stripes, 4);
            List<BoxMotion.Allotment> byTravel = BoxMotion.allotForTravel(stripes, 4);
            assertThat(byCount).extracting(BoxMotion.Allotment::colour).containsExactly("red", "gold", "blue", "green");
            assertThat(byTravel)
                    .extracting(BoxMotion.Allotment::colour)
                    .containsExactly("red", "blue", "gold", "green");
            assertThat(BoxMotion.boxTravel(BoxMotion.boxChain(stripes, byCount)))
                    .isEqualTo(14);
            assertThat(BoxMotion.boxTravel(BoxMotion.boxChain(stripes, byTravel)))
                    .isEqualTo(10);
        }

        @Test
        void keepsTheCommonestColourResting() {
            assertThat(BoxMotion.allotForTravel(stripes, 4).get(0)).isEqualTo(new BoxMotion.Allotment("red", 1, 9));
        }

        @Test
        void leavesAPatternTheCountAlreadySuitsAsItWas() {
            List<String> simple = List.of("ground", "ground", "ground", "blue", "blue", "gold");
            assertThat(BoxMotion.allotForTravel(simple, 4)).isEqualTo(BoxMotion.allotBoxes(simple, 4));
            assertThat(BoxMotion.allotForTravel(List.of("ground"), 1))
                    .containsExactly(new BoxMotion.Allotment("ground", 1, 1));
        }

        @Test
        void stillRefusesMoreColoursThanBoxes() {
            assertThatThrownBy(() -> BoxMotion.allotForTravel(stripes, 3)).isInstanceOf(CapacityException.class);
        }

        @Test
        void readsTheChangesAndTheLongestLiftOffTheSameSteps() {
            List<Integer> chain = BoxMotion.boxChain(stripes, BoxMotion.allotForTravel(stripes, 4));
            assertThat(BoxMotion.boxChanges(chain)).isEqualTo(8);
            assertThat(BoxMotion.longestLift(chain)).isEqualTo(2);
            assertThat(BoxMotion.longestLift(chain, false)).isEqualTo(2);
        }
    }
}
