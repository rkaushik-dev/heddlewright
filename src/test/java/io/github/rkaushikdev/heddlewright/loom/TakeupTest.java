package io.github.rkaushikdev.heddlewright.loom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TakeupTest {

    private static final double ROLLER = 15 / Math.PI;

    /** The train every reading starts from, with one wheel or the roller changed. */
    private static double dividend(int ratchet, int carrier, int stud, int roller, double diameter) {
        return Takeup.takeUpDividend(ratchet, carrier, stud, roller, diameter);
    }

    private static double train() {
        return dividend(50, 89, 15, 90, ROLLER);
    }

    @Nested
    class TakeUpDividend {

        @Test
        void collapsesTheWholeTrainIntoOneNumber() {
            assertThat(train()).isEqualTo(1780);
        }

        @Test
        void risesWithTheRatchetAndFallsWithTheStudWheel() {
            double base = train();
            assertThat(dividend(100, 89, 15, 90, ROLLER)).isGreaterThan(base);
            assertThat(dividend(50, 89, 30, 90, ROLLER)).isLessThan(base);
        }

        @Test
        void fallsAsTheRollerGetsFatter() {
            double base = train();
            assertThat(dividend(50, 89, 15, 90, 10)).isLessThan(base);
        }

        @Test
        void refusesAWheelWithNoTeeth() {
            assertThatThrownBy(() -> dividend(50, 89, 0, 90, ROLLER)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class PicksPerInch {

        @Test
        void isTheDividendOverTheChangeWheel() {
            assertThat(Takeup.picksPerInch(1780, 40)).isEqualTo(44.5);
            assertThat(Takeup.picksPerInch(1780, 89)).isEqualTo(20);
        }

        @Test
        void givesACloserSettOnASmallerWheel() {
            assertThat(Takeup.picksPerInch(1780, 30)).isGreaterThan(Takeup.picksPerInch(1780, 60));
        }

        @Test
        void refusesAWheelWithAFractionalToothCount() {
            assertThatThrownBy(() -> Takeup.picksPerInch(1780, 40.5)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class ClothPerPick {

        @Test
        void isTheReciprocalOfTheSett() {
            assertThat(Takeup.clothPerPick(1780, 89)).isEqualTo(0.05);
        }
    }

    @Nested
    class ExactChangeWheel {

        @Test
        void isTheWheelAWantedSettWouldNeedIfTeethWereContinuous() {
            assertThat(Takeup.exactChangeWheel(1780, 40)).isEqualTo(44.5);
            assertThat(Takeup.exactChangeWheel(1780, 44.5)).isEqualTo(40);
        }
    }

    @Nested
    class BestChangeWheel {

        @Test
        void picksTheWheelThatLandsNearestAndSaysHowFarOffItIs() {
            Takeup.BestWheel best = Takeup.bestChangeWheel(1780, 44.5);
            assertThat(best.teeth()).isEqualTo(40);
            assertThat(best.achieved()).isEqualTo(44.5);
            assertThat(best.error()).isEqualTo(0);
        }

        @Test
        void reportsTheErrorWhenNoWheelLandsExactly() {
            Takeup.BestWheel best = Takeup.bestChangeWheel(1780, 43);
            assertThat(best.exact()).isCloseTo(41.395, within(0.005));
            assertThat(List.of(40, 42)).contains(best.teeth());
            assertThat(best.error()).isGreaterThan(0);
            assertThat(best.errorPercent()).isCloseTo((best.error() / 43) * 100, within(0.0005));
        }

        @Test
        void takesADifferentDrawerOfWheels() {
            Takeup.BestWheel best = Takeup.bestChangeWheel(1780, 43, List.of(41));
            assertThat(best.teeth()).isEqualTo(41);
        }

        @Test
        void listsTheStockDrawerInAscendingOrder() {
            List<Integer> wheels = Takeup.stockChangeWheels();
            assertThat(wheels).isSorted();
        }
    }

    @Nested
    class SettingCard {

        @Test
        void givesASettForEveryWheelInTheDrawer() {
            List<Takeup.Setting> card = Takeup.settingCard(1780);
            assertThat(card).hasSameSizeAs(Takeup.stockChangeWheels());
            assertThat(card.get(0)).isEqualTo(new Takeup.Setting(24, Takeup.picksPerInch(1780, 24)));
        }

        @Test
        void fallsAsTheWheelsGetBigger() {
            List<Takeup.Setting> card = Takeup.settingCard(1780);
            for (int i = 1; i < card.size(); i += 1) {
                assertThat(card.get(i).picksPerInch())
                        .isLessThan(card.get(i - 1).picksPerInch());
            }
        }
    }

    @Nested
    class SettRange {

        @Test
        void spansTheDrawer() {
            Takeup.SettRange range = Takeup.settRange(1780);
            assertThat(range.highest()).isEqualTo(Takeup.picksPerInch(1780, 24));
            assertThat(range.lowest()).isEqualTo(Takeup.picksPerInch(1780, 60));
        }
    }

    @Nested
    class AssertSettReachable {

        @Test
        void passesASettAWheelReaches() {
            assertThat(Takeup.assertSettReachable(1780, 44.5).teeth()).isEqualTo(40);
        }

        @Test
        void refusesOneNothingInTheDrawerComesNear() {
            InvalidArgumentException err =
                    catchThrowableOfType(InvalidArgumentException.class, () -> Takeup.assertSettReachable(1780, 200));
            assertThat(err).isNotNull();
            assertThat(err.detail().get("wantedPicksPerInch")).isEqualTo(Json.of(200));
            assertThat(err.detail().get("teeth")).isEqualTo(Json.of(24));
        }

        @Test
        void takesAWiderTolerance() {
            assertThat(Takeup.assertSettReachable(1780, 43, 2).teeth()).isGreaterThan(0);
        }
    }

    @Nested
    class OffTheLoom {

        @Test
        void closesThePicksUpAsTheClothRelaxes() {
            assertThat(Takeup.offLoomPicks(24, 4)).isEqualTo(25);
            assertThat(Takeup.loomPicksFor(25, 4)).isEqualTo(24);
            assertThat(Takeup.offLoomPicks(24, 0)).isEqualTo(24);
        }

        @Test
        void choosesTheWheelForTheLoomSettTheFinishedClothNeeds() {
            Takeup.OffLoom chosen = Takeup.bestChangeWheelOffLoom(1200, 25, 4, List.of(48, 50, 52));
            assertThat(chosen.onLoom().teeth()).isEqualTo(50);
            assertThat(chosen.offLoomPicks()).isEqualTo(25);
            assertThat(chosen.offLoomError()).isEqualTo(0);
            assertThat(Takeup.bestChangeWheel(1200, 25, List.of(48, 50, 52)).teeth())
                    .isEqualTo(48);
        }

        @Test
        void readsTheDrawersRangeAsTheClothWillMeasure() {
            Takeup.SettRange off = Takeup.settRangeOffLoom(1200, List.of(40, 60), 4);
            assertThat(off.lowest()).isEqualTo(20.833);
            assertThat(off.highest()).isEqualTo(31.25);
            assertThat(Takeup.settRange(1200, List.of(40, 60))).isEqualTo(new Takeup.SettRange(20, 30));
        }

        @Test
        void keepsAnEmptyDrawerReachingNothing() {
            Takeup.SettRange none = Takeup.settRangeOffLoom(1200, List.of(), 4);
            assertThat(none.lowest()).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(none.highest()).isEqualTo(Double.NEGATIVE_INFINITY);
        }

        @Test
        void refusesAContractionNoClothHas() {
            assertThatThrownBy(() -> Takeup.offLoomPicks(24, 25)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Takeup.loomPicksFor(24, -1)).isInstanceOf(InvalidArgumentException.class);
            assertThatThrownBy(() -> Takeup.loomPicksFor(0, 3)).isInstanceOf(InvalidArgumentException.class);
        }
    }
}
