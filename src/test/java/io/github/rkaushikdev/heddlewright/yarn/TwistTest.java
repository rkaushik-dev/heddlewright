package io.github.rkaushikdev.heddlewright.yarn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.yarn.Twist.Direction;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TwistTest {

    @Nested
    class TwistMultiplier {

        @Test
        void isTheTurnsOverTheSquareRootOfTheCount() {
            assertThat(Twist.twistMultiplier(20, 25)).isEqualTo(4);
        }

        @Test
        void readsAFinerYarnAtFewerTurnsAsALowerMultiplier() {
            assertThat(Twist.twistMultiplier(20, 100)).isLessThan(Twist.twistMultiplier(20, 25));
        }

        @Test
        void invertsThroughTurnsForMultiplier() {
            double turns = Twist.turnsForMultiplier(4, 25);
            assertThat(turns).isEqualTo(20);
            assertThat(Twist.twistMultiplier(turns, 25)).isCloseTo(4, within(0.5e-3));
        }

        @Test
        void readsACountGivenInAnotherSystem() {
            double asNe = Twist.twistMultiplier(20, 25, "ne");
            double asTex = Twist.twistMultiplier(20, 590.5414 / 25, "tex");
            assertThat(asTex).isCloseTo(asNe, within(0.5e-3));
        }
    }

    @Nested
    class MetricTwistFactor {

        @Test
        void isTheMetricReadingOfTheSameIdea() {
            assertThat(Twist.metricTwistFactor(800, 25, "tex")).isGreaterThan(0);
        }

        @Test
        void risesWithTheTurns() {
            double low = Twist.metricTwistFactor(400, 25, "tex");
            double high = Twist.metricTwistFactor(800, 25, "tex");
            assertThat(high).isCloseTo(low * 2, within(0.5e-3));
        }
    }

    @Nested
    class TurnsPerMetreAndTurnsPerInch {

        @Test
        void convertBothWays() {
            assertThat(Twist.turnsPerMetre(20)).isCloseTo(787.4, within(0.05));
            assertThat(Twist.turnsPerInch(Twist.turnsPerMetre(20))).isCloseTo(20, within(0.5e-3));
        }
    }

    @Nested
    class MultiplierFor {

        @Test
        void givesTheWorkingFigureForEachPurpose() {
            assertThat(Twist.multiplierFor("warp")).isEqualTo(4);
            assertThat(Twist.multiplierFor("weft")).isEqualTo(3);
            assertThat(Twist.multiplierFor("voile")).isGreaterThan(Twist.multiplierFor("warp"));
        }

        @Test
        void namesThePurposesItKnowsWhenItDoesNotKnowOne() {
            InvalidArgumentException err =
                    catchThrowableOfType(InvalidArgumentException.class, () -> Twist.multiplierFor("rope"));
            assertThat(err).isNotNull();
            assertThat(((JsonArray) err.detail().get("known")).items()).contains(Json.of("warp"));
        }
    }

    @Nested
    class TwistClass {

        @Test
        void callsAYarnAtItsWorkingMultiplierNormal() {
            assertThat(Twist.twistClass(20, 25, "ne", "warp").reading()).isEqualTo("normal");
        }

        @Test
        void callsALightlyTwistedWarpYarnSoft() {
            assertThat(Twist.twistClass(12, 25, "ne", "warp").reading()).isEqualTo("soft");
        }

        @Test
        void callsAHeavilyTwistedOneHard() {
            assertThat(Twist.twistClass(30, 25, "ne", "warp").reading()).isEqualTo("hard");
        }

        @Test
        void readsTheSameYarnDifferentlyForADifferentPurpose() {
            assertThat(Twist.twistClass(20, 25, "ne", "warp").reading()).isEqualTo("normal");
            assertThat(Twist.twistClass(20, 25, "ne", "weft").reading()).isEqualTo("hard");
        }
    }

    @Nested
    class FoldedDirectionAndFoldingTurns {

        @Test
        void foldsAgainstTheSingleTwist() {
            assertThat(Direction.DIRECTIONS.stream().map(Direction::toString)).containsExactly("S", "Z");
            assertThat(Twist.foldedDirection(Direction.Z)).isEqualTo(Direction.S);
            assertThat(Twist.foldedDirection(Direction.S)).isEqualTo(Direction.Z);
        }

        @Test
        void refusesADirectionItDoesNotKnow() {
            assertThatThrownBy(() -> Twist.foldedDirection(Direction.parse("X", "singleDirection")))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void foldsAtAShareOfTheSingleTwist() {
            assertThat(Twist.foldingTurns(20)).isEqualTo(14);
            assertThat(Twist.foldingTurns(20, 1)).isEqualTo(20);
        }

        @Test
        void refusesAFoldingShareNobodyWouldSpin() {
            assertThatThrownBy(() -> Twist.foldingTurns(20, 0)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class TwillContrast {

        @Test
        void isPronouncedWhenTheTwoSystemsTwistOppositeWays() {
            assertThat(Twist.twillContrast(Direction.Z, Direction.S)).isEqualTo("pronounced");
        }

        @Test
        void flattensWhenTheTwistRunsTheSameWayAsTheTwill() {
            assertThat(Twist.twillContrast(Direction.Z, Direction.Z, Direction.Z))
                    .isEqualTo("flattened");
        }

        @Test
        void standsOutWhenTheTwistRunsAgainstTheTwill() {
            assertThat(Twist.twillContrast(Direction.S, Direction.S, Direction.Z))
                    .isEqualTo("pronounced");
        }
    }

    @Nested
    class Contraction {

        @Test
        void readsTheShorteningAsAShareOfTheStrand() {
            assertThat(Twist.twistContraction(100, 97)).isEqualTo(3);
            assertThat(Twist.twistContraction(1, 1)).isEqualTo(0);
        }

        @Test
        void refusesAYarnThatCameOutLonger() {
            assertThatThrownBy(() -> Twist.twistContraction(100, 101))
                    .isInstanceOf(InvalidArgumentException.class)
                    .hasMessageContaining("longer");
        }

        @Test
        void makesAFoldedIndirectCountCoarserByTheContraction() {
            assertThat(Twist.foldedCount(List.of(40, 40), "ne", 0)).isEqualTo(20);
            assertThat(Twist.foldedCount(List.of(40, 40), "ne", 4)).isEqualTo(19.2);
        }

        @Test
        void makesAFoldedDirectCountHeavierByTheContraction() {
            assertThat(Twist.foldedCount(List.of(15, 15), "tex", 4)).isEqualTo(31.25);
            assertThat(Twist.foldedCount(List.of(15, 15), "tex", 4))
                    .isGreaterThan(Count.foldedCount(List.of(15, 15), "tex"));
        }

        @Test
        void agreesBetweenTheSystemsOnceContracted() {
            double ne = Twist.foldedCount(List.of(40, 40), "ne", 3);
            double tex = Twist.foldedCount(List.of(Count.toTex(40, "ne"), Count.toTex(40, "ne")), "tex", 3);
            assertThat(Count.toTex(ne, "ne")).isCloseTo(tex, within(0.001));
        }

        @Test
        void refusesAContractionNoTwisterCouldGive() {
            assertThatThrownBy(() -> Twist.foldedCount(List.of(40, 40), "ne", 60))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Twist.foldedCount(List.of(40, 40), "ne", -1))
                    .isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void classesATwistJustInsideTheToleranceAsNormal() {
            double turns = Twist.turnsForMultiplier(4 * (1 - Twist.CLASS_TOLERANCE) + 0.01, 25);
            assertThat(Twist.twistClass(turns, 25).reading()).isEqualTo("normal");
            assertThat(Twist.twistClass(Twist.turnsForMultiplier(3.3, 25), 25).reading())
                    .isEqualTo("soft");
            assertThat(Twist.twistClass(Twist.turnsForMultiplier(4.7, 25), 25).reading())
                    .isEqualTo("hard");
        }

        @Test
        void readsTheMultiplierOfATexYarnOffTheDerivedCottonConstant() {
            assertThat(Twist.twistMultiplier(20, Count.toTex(25, "ne"), "tex")).isEqualTo(4);
            assertThat(Twist.metricTwistFactor(1000, 25, "nm")).isEqualTo(200);
        }
    }
}
