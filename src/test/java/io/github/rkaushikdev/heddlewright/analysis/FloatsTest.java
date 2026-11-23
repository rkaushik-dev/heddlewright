package io.github.rkaushikdev.heddlewright.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.weave.Plain;
import io.github.rkaushikdev.heddlewright.weave.Satin;
import io.github.rkaushikdev.heddlewright.weave.Twill;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FloatsTest {

    @Nested
    class WarpFloats {

        @Test
        void findsOneFloatPerEndInA22TwillEachTwoThreadsLong() {
            List<Floats.WarpFloat> floats = Floats.warpFloats(Twill.twillWeave("2/2"));
            assertThat(floats).hasSize(4);
            assertThat(floats).allMatch(found -> found.length() == 2);
        }

        @Test
        void measuresRoundTheRepeatJoiningAFloatThatWraps() {
            List<Floats.WarpFloat> onFirstEnd = Floats.warpFloats(Matrix.parse("X.\nX.\n..\nX.")).stream()
                    .filter(found -> found.end() == 1)
                    .toList();
            assertThat(onFirstEnd).hasSize(1);
            assertThat(onFirstEnd.get(0).length()).isEqualTo(3);
            assertThat(onFirstEnd.get(0).wraps()).isTrue();
        }

        @Test
        void findsNothingOnAnEndThatIsNeverRaised() {
            assertThat(Floats.warpFloats(Matrix.parse(".X\n.X")).stream().filter(found -> found.end() == 1))
                    .isEmpty();
        }

        @Test
        void readsAPlainWeaveAsFloatsOfOne() {
            assertThat(Floats.warpFloats(Plain.plainWeave())).allMatch(found -> found.length() == 1);
        }
    }

    @Nested
    class WeftFloats {

        @Test
        void findsTheWeftFloatsOfA13Twill() {
            List<Floats.WeftFloat> floats = Floats.weftFloats(Twill.twillWeave("1/3"));
            assertThat(floats).hasSize(4);
            assertThat(floats).allMatch(found -> found.length() == 3);
        }

        @Test
        void readsTheDownCellsNotTheUpOnes() {
            assertThat(Floats.weftFloats(Matrix.parse("X..."))).containsExactly(new Floats.WeftFloat(1, 2, 3, false));
        }
    }

    @Nested
    class LongestFloat {

        @Test
        void reportsEachDirectionAndWhichThreadCarriesIt() {
            Floats.Longest found = Floats.longestFloat(Twill.twillWeave("3/1"));
            assertThat(found.warp()).isEqualTo(3);
            assertThat(found.weft()).isEqualTo(1);
            assertThat(found.longest()).isEqualTo(3);
            assertThat(found.onEnd().getAsInt()).isGreaterThan(0);
        }

        @Test
        void isOneEachWayForPlainWeave() {
            Floats.Longest found = Floats.longestFloat(Plain.plainWeave());
            assertThat(found.warp()).isEqualTo(1);
            assertThat(found.weft()).isEqualTo(1);
            assertThat(found.longest()).isEqualTo(1);
        }

        @Test
        void measuresAFiveThreadSatinAtFour() {
            assertThat(Floats.longestFloat(Satin.satinWeave(5, 2)).warp()).isEqualTo(4);
        }

        @Test
        void reportsANullThreadWhenADirectionNeverFloats() {
            Floats.Longest found = Floats.longestFloat(Matrix.parse("XX\nXX"));
            assertThat(found.weft()).isEqualTo(0);
            assertThat(found.onPick()).isEmpty();
            assertThat(found.toJson().get("onPick")).isEqualTo(Json.NULL);
        }
    }

    @Nested
    class FloatsOver {

        @Test
        void findsOnlyFloatsPastTheLimitAndTagsTheDirection() {
            List<Floats.Over> found = Floats.floatsOver(Twill.twillWeave("3/1"), 2);
            assertThat(found).hasSize(4);
            assertThat(found).allMatch(entry -> entry.direction().equals("warp"));
        }

        @Test
        void isEmptyWhenTheClothIsInsideTheLimit() {
            assertThat(Floats.floatsOver(Plain.plainWeave(), 1)).isEmpty();
        }

        @Test
        void refusesALimitOfZero() {
            assertThatThrownBy(() -> Floats.floatsOver(Plain.plainWeave(), 0))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class FloatHistogram {

        @Test
        void bucketsFloatsByLengthInEachDirection() {
            Floats.Histogram histogram = Floats.floatHistogram(Twill.twillWeave("3/1"));
            assertThat(histogram.warp().get(3)).isEqualTo(4);
            assertThat(histogram.weft().get(1)).isEqualTo(4);
            assertThat(histogram.warp()).doesNotContainKey(1);
        }
    }

    @Nested
    class MeanFloatLength {

        @Test
        void isOneEachWayForPlainWeave() {
            assertThat(Floats.meanFloatLength(Plain.plainWeave())).isEqualTo(new WarpWeft(1, 1));
        }

        @Test
        void showsTheLongWarpFloatAndTheSingleBindingPointOfASatin() {
            WarpWeft satin = Floats.meanFloatLength(Satin.satinWeave(8, 3));
            assertThat(satin.warp()).isEqualTo(7);
            assertThat(satin.weft()).isEqualTo(1);
        }

        @Test
        void swapsTheTwoReadingsWhenTheSatinIsWovenWeftFaceUp() {
            WarpWeft sateen = Floats.meanFloatLength(Satin.satinWeave(8, 3, Satin.Face.WEFT));
            assertThat(sateen.warp()).isEqualTo(1);
            assertThat(sateen.weft()).isEqualTo(7);
        }

        @Test
        void isZeroForADirectionWithNoFloatsAtAll() {
            assertThat(Floats.meanFloatLength(Matrix.parse("XX\nXX")).weft()).isEqualTo(0);
        }
    }

    @Nested
    class FloatLimitForSett {

        @Test
        void allowsALongerFloatOnACoarseSettThanOnACloseOne() {
            assertThat(Floats.floatLimitForSett(24)).isEqualTo(2);
            assertThat(Floats.floatLimitForSett(60)).isEqualTo(6);
            assertThat(Floats.floatLimitForSett(120)).isEqualTo(12);
        }

        @Test
        void neverFallsBelowOneThread() {
            assertThat(Floats.floatLimitForSett(4)).isEqualTo(1);
        }

        @Test
        void takesADifferentSpanWhenTheClothWantsOne() {
            assertThat(Floats.floatLimitForSett(24, 0.25)).isEqualTo(6);
        }
    }

    @Nested
    class WithinFloatLimit {

        @Test
        void passesAClothInsideTheLimitAndFailsOnePastIt() {
            assertThat(Floats.withinFloatLimit(Twill.twillWeave("2/2"), 2)).isTrue();
            assertThat(Floats.withinFloatLimit(Twill.twillWeave("3/1"), 2)).isFalse();
            assertThat(Floats.withinFloatLimit(Satin.satinWeave(8, 3), 7)).isTrue();
        }
    }

    @Nested
    class FloatOnEndAndFloatOnPick {

        private final Matrix cloth = Twill.twillWeave("3/1");

        @Test
        void measuresOneNamedThread() {
            assertThat(Floats.floatOnEnd(cloth, 1)).isEqualTo(3);
            assertThat(Floats.floatOnPick(cloth, 1)).isEqualTo(1);
        }

        @Test
        void agreesWithTheWholeClothReading() {
            Floats.Longest longest = Floats.longestFloat(cloth);
            assertThat(Floats.floatOnEnd(cloth, longest.onEnd().getAsInt())).isEqualTo(longest.warp());
        }

        @Test
        void refusesAThreadNumberOfZero() {
            assertThatThrownBy(() -> Floats.floatOnEnd(cloth, 0)).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class TheBack {

        @Test
        void readsTheWeftFloatsOfTheFaceAsWarpFloatsOnTheBack() {
            Matrix twill = Twill.twillWeave("1/3");
            assertThat(Floats.warpFloats(twill, Floats.Side.BACK)).hasSize(4).allMatch(found -> found.length() == 3);
            assertThat(Floats.weftFloats(twill, Floats.Side.BACK)).hasSize(4).allMatch(found -> found.length() == 1);
        }

        @Test
        void putsTheLongFloatOnTheOtherDirectionOfTheBack() {
            Floats.Longest face = Floats.longestFloat(Twill.twillWeave("1/3"));
            Floats.Longest back = Floats.longestFloat(Twill.twillWeave("1/3"), Floats.Side.BACK);
            assertThat(face.weft()).isEqualTo(3);
            assertThat(face.warp()).isEqualTo(1);
            assertThat(back.warp()).isEqualTo(3);
            assertThat(back.weft()).isEqualTo(1);
            assertThat(back.onEnd()).hasValue(1);
        }

        @Test
        void seesABalancedTwillTheSameFromEitherSide() {
            Matrix twill = Twill.twillWeave("2/2");
            assertThat(Floats.longestFloat(twill, Floats.Side.BACK)).isEqualTo(Floats.longestFloat(twill));
            assertThat(Floats.floatsOver(twill, 1, Floats.Side.BACK)).hasSize(8);
        }

        @Test
        void findsTheWholeEndFloatingOnTheBackWhenItIsNeverRaised() {
            Matrix cloth = Matrix.parse(".X\n.X\n.X");
            assertThat(Floats.warpFloats(cloth))
                    .extracting(Floats.WarpFloat::end)
                    .containsExactly(2);
            assertThat(Floats.warpFloats(cloth, Floats.Side.BACK))
                    .containsExactly(new Floats.WarpFloat(1, 1, 3, false));
        }

        @Test
        void reportsTheBackFloatsPastALimitWarpFirst() {
            Matrix satin = Satin.satinWeave(5, 2);
            List<Floats.Over> back = Floats.floatsOver(satin, 2, Floats.Side.BACK);
            List<Floats.Over> face = Floats.floatsOver(satin, 2);
            assertThat(back).hasSize(5).allMatch(found -> found.length() == 4);
            assertThat(face).hasSize(5);
            assertThat(back.get(0).direction()).isNotEqualTo(face.get(0).direction());
        }

        @Test
        void takesTheWorseSideForASatinAndTheSameForATwillThatBalances() {
            assertThat(Floats.longestEitherSide(Twill.twillWeave("1/3"))).isEqualTo(3);
            assertThat(Floats.longestEitherSide(Twill.twillWeave("2/2"))).isEqualTo(2);
            assertThat(Floats.longestEitherSide(Satin.satinWeave(5, 2))).isEqualTo(4);
        }
    }
}
