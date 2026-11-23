package io.github.rkaushikdev.heddlewright.warp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.heddlewright.util.CapacityException;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Problem;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReedTest {

    @Nested
    class ReedFor {

        @Test
        void dividesTheSettByTheEndsInEachDent() {
            assertThat(Reed.reedFor(24, 2)).isEqualTo(12);
            assertThat(Reed.reedFor(24, 3)).isEqualTo(8);
            assertThat(Reed.reedFor(24, 1)).isEqualTo(24);
        }

        @Test
        void doesNotHideASettThatNeedsAFractionalReed() {
            assertThat(Reed.reedFor(25, 2)).isEqualTo(12.5);
        }
    }

    @Nested
    class NearestReed {

        @Test
        void picksTheClosestReedOnTheRackAndSaysHowFarOffItIs() {
            assertThat(Reed.nearestReed(12.5)).isEqualTo(new Reed.Nearest(12, -0.5));
            assertThat(Reed.nearestReed(13)).isEqualTo(new Reed.Nearest(12, -1));
            assertThat(Reed.nearestReed(17)).isEqualTo(new Reed.Nearest(16, -1));
        }

        @Test
        void isExactWhenTheWantedCountIsOnTheRack() {
            assertThat(Reed.nearestReed(15)).isEqualTo(new Reed.Nearest(15, 0));
        }

        @Test
        void takesADifferentRack() {
            assertThat(Reed.nearestReed(13, List.of(11, 13, 17))).isEqualTo(new Reed.Nearest(13, 0));
        }

        @Test
        void listsTheReedsItKnowsAbout() {
            assertThat(Reed.STOCK_REEDS).contains(12, 30);
        }
    }

    @Nested
    class ReedOptions {

        @Test
        void findsEveryWholeNumberWayToReachASett() {
            assertThat(Reed.reedOptions(24))
                    .containsExactly(
                            new Reed.Option(6, 4),
                            new Reed.Option(8, 3),
                            new Reed.Option(12, 2),
                            new Reed.Option(24, 1));
        }

        @Test
        void respectsALowerLimitOnHowManyEndsMayShareADent() {
            assertThat(Reed.reedOptions(24, 2)).containsExactly(new Reed.Option(12, 2), new Reed.Option(24, 1));
        }

        @Test
        void isEmptyWhenNoStockReedDividesTheSett() {
            assertThat(Reed.reedOptions(23)).isEmpty();
        }
    }

    @Nested
    class DentingPlan {

        @Test
        void fillsDentsEvenlyForAPlainPattern() {
            assertThat(Reed.dentingPlan(8, List.of(2))).containsExactly(2, 2, 2, 2);
        }

        @Test
        void alternatesAnUnevenPatternWhichIsHowAHalfSettIsSleyed() {
            assertThat(Reed.dentingPlan(10, List.of(3, 2))).containsExactly(3, 2, 3, 2);
        }

        @Test
        void cutsTheLastDentShortRatherThanOverfillingIt() {
            assertThat(Reed.dentingPlan(7, List.of(2))).containsExactly(2, 2, 2, 1);
        }

        @Test
        void refusesAGroupOfNoEndsWhichWouldNeverFinish() {
            assertThatThrownBy(() -> Reed.dentingPlan(8, List.of(0))).isInstanceOf(InvalidArgumentException.class);
        }

        @Test
        void placesExactlyTheEndsItWasGiven() {
            for (List<Integer> pattern : List.of(List.of(2), List.of(3, 2), List.of(1, 2, 3))) {
                assertThat(Reed.dentingPlan(17, pattern).stream()
                                .mapToInt(Integer::intValue)
                                .sum())
                        .isEqualTo(17);
            }
        }
    }

    @Nested
    class AverageEndsPerDent {

        @Test
        void averagesARepeatingPattern() {
            assertThat(Reed.averageEndsPerDent(List.of(2))).isEqualTo(2);
            assertThat(Reed.averageEndsPerDent(List.of(3, 2))).isEqualTo(2.5);
            assertThat(Reed.averageEndsPerDent(List.of(1, 2, 3))).isEqualTo(2);
        }
    }

    @Nested
    class ReedWidth {

        @Test
        void dividesTheDentsByTheReedCount() {
            assertThat(Reed.reedWidth(240, 12)).isEqualTo(20);
            assertThat(Reed.reedWidth(100, 8)).isEqualTo(12.5);
        }
    }

    @Nested
    class SleyOrder {

        @Test
        void saysWhichDentEachEndFallsIn() {
            assertThat(Reed.sleyOrder(6, List.of(2))).containsExactly(1, 1, 2, 2, 3, 3);
            assertThat(Reed.sleyOrder(5, List.of(3, 2))).containsExactly(1, 1, 1, 2, 2);
        }

        @Test
        void hasOneEntryPerEnd() {
            assertThat(Reed.sleyOrder(17, List.of(3, 2))).hasSize(17);
        }
    }

    @Nested
    class DentingWarnings {

        @Test
        void isQuietOnAnEvenPlan() {
            assertThat(Reed.dentingWarnings(List.of(2, 2, 2, 2))).isEmpty();
        }

        @Test
        void flagsACrowdedDentAndNamesWhichOnes() {
            List<Problem> warnings = Reed.dentingWarnings(List.of(2, 6, 2, 5));
            assertThat(warnings.get(0).code()).isEqualTo("crowded_dent");
            assertThat(warnings.get(0).detail().get("dents")).isEqualTo(Json.arr(2, 4));
        }

        @Test
        void flagsAShortLastDent() {
            assertThat(Reed.dentingWarnings(List.of(2, 2, 1)).stream().map(Problem::code))
                    .contains("short_last_dent");
        }

        @Test
        void takesADifferentCrowdingLimit() {
            assertThat(Reed.dentingWarnings(List.of(3, 3), 2).get(0).code()).isEqualTo("crowded_dent");
            assertThat(Reed.dentingWarnings(List.of(3, 3), 4)).isEmpty();
        }
    }

    @Nested
    class AssertDentingFits {

        @Test
        void passesAPlanTheReedCanHold() {
            assertThat(Reed.assertDentingFits(List.of(2, 2), 10)).containsExactly(2, 2);
        }

        @Test
        void refusesOneItCannotNamingBothCounts() {
            CapacityException err =
                    catchThrowableOfType(CapacityException.class, () -> Reed.assertDentingFits(List.of(2, 2, 2), 2));
            // A reed that is too small is a capacity problem rather than a fault in the draft, and it
            // reports the shortfall the same way every other capacity check does.
            assertThat(err).isNotNull();
            assertThat(err.detail().get("needed")).isEqualTo(Json.of(3));
            assertThat(err.detail().get("dentsAvailable")).isEqualTo(Json.of(2));
            assertThat(err.detail().get("over")).isEqualTo(Json.of(1));
        }
    }

    @Nested
    class ReedCounts {

        @Test
        void readsAStockportCountAsTheDentsInTwoInches() {
            assertThat(Reed.ReedCount.STOCKPORT.dentsPerInch(24)).isEqualTo(12);
            assertThat(Reed.ReedCount.STOCKPORT.count(12)).isEqualTo(24);
        }

        @Test
        void readsAMetricCountAsTheDentsInTenCentimetres() {
            assertThat(Reed.ReedCount.METRIC.dentsPerInch(47)).isEqualTo(11.938);
            assertThat(Reed.ReedCount.METRIC.count(12)).isEqualTo(47.24);
            assertThat(Reed.ReedCount.PER_INCH.dentsPerInch(12)).isEqualTo(12);
        }

        @Test
        void picksTheNearestReedOffARackStampedInAnotherCount() {
            assertThat(Reed.nearestReed(12, List.of(40, 47, 55), Reed.ReedCount.METRIC))
                    .isEqualTo(new Reed.Nearest(47, -0.062));
            assertThat(Reed.nearestReed(12.5, List.of(20, 24, 26), Reed.ReedCount.STOCKPORT))
                    .isEqualTo(new Reed.Nearest(24, -0.5));
        }

        @Test
        void refusesACountOfNothingOrARackWithNoStamp() {
            assertThatThrownBy(() -> Reed.ReedCount.METRIC.dentsPerInch(0))
                    .isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Reed.nearestReed(12, List.of(24), null))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class SelvedgeDenting {

        @Test
        void putsTheHeavierSelvedgeDentsAtEachSideOfTheGround() {
            assertThat(Reed.selvedgeDenting(20, List.of(2), 8, 4)).containsExactly(4, 4, 2, 2, 4, 4);
        }

        @Test
        void endsTheRightSelvedgeOnItsShortDentSoItMirrorsTheLeft() {
            assertThat(Reed.selvedgeDenting(22, List.of(3, 2), 6, 4)).containsExactly(4, 2, 3, 2, 3, 2, 2, 4);
            assertThat(Reed.selvedgeDenting(22, List.of(3, 2), 6, 4).stream()
                            .mapToInt(Integer::intValue)
                            .sum())
                    .isEqualTo(22);
        }

        @Test
        void raisesNoShortDentWarningForASelvedgeDentedLikeItsFellow() {
            assertThat(Reed.dentingWarnings(Reed.selvedgeDenting(20, List.of(2), 8, 4)))
                    .isEmpty();
        }

        @Test
        void sleysAnyDentingEndByEnd() {
            assertThat(Reed.sley(List.of(4, 2, 4))).containsExactly(1, 1, 1, 1, 2, 2, 3, 3, 3, 3);
            assertThat(Reed.sley(Reed.dentingPlan(5, List.of(3, 2)))).isEqualTo(Reed.sleyOrder(5, List.of(3, 2)));
        }

        @Test
        void refusesSelvedgesThatLeaveNoGround() {
            assertThatThrownBy(() -> Reed.selvedgeDenting(16, List.of(2), 8, 4)).isInstanceOf(CapacityException.class);
            assertThatThrownBy(() -> Reed.selvedgeDenting(20, List.of(2), 8, 0))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }
}
