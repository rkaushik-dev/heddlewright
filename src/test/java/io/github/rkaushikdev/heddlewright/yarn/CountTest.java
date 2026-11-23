package io.github.rkaushikdev.heddlewright.yarn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.yarn.Count.Indirect;
import io.github.rkaushikdev.heddlewright.yarn.Count.Kind;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CountTest {

    private static Offset<Double> digits(int count) {
        return within(Math.pow(10, -count) / 2);
    }

    private static double constant(String system) {
        return ((Indirect) Count.COUNT_SYSTEMS.get(system)).constant();
    }

    @Nested
    class CountSystems {

        @Test
        void carriesTheSystemsAMillQuotesInTheMetricAndCottonOnesFirst() {
            assertThat(Count.COUNT_SYSTEMS.keySet())
                    .containsExactly(
                            "tex",
                            "dtex",
                            "denier",
                            "nm",
                            "ne",
                            "new",
                            "nel",
                            "nw",
                            "jute",
                            "run",
                            "cut",
                            "galashiels");
        }

        @Test
        void worksTheJuteSpyndleOutToThirtyFourAndAHalfTex() {
            assertThat(((Count.Direct) Count.COUNT_SYSTEMS.get("jute")).factor())
                    .isCloseTo(34.45, digits(2));
            assertThat(Count.toTex(8, "jute")).isCloseTo(275.59, digits(2));
            assertThat(Count.isIndirect("jute")).isFalse();
        }

        @Test
        void worksTheAmericanAndGalashielsWoollenConstantsOut() {
            assertThat(constant("run")).isCloseTo(310.03, digits(2));
            assertThat(constant("cut")).isCloseTo(constant("nel"), digits(6));
            assertThat(constant("galashiels")).isCloseTo(2480.27, digits(2));
        }

        @Test
        void convertsBetweenTheWoollenSystems() {
            assertThat(Count.convertCount(5, "run", "nm")).isEqualTo(16.1273);
            assertThat(Count.convertCount(24, "galashiels", "run")).isCloseTo(3, within(0.001));
            assertThat(Count.convertCount(1, "jute", "ne")).isCloseTo(17.1429, digits(4));
        }

        @Test
        void keepsTheDirectSystemsInStepWithTex() {
            assertThat(((Count.Direct) Count.COUNT_SYSTEMS.get("denier")).factor())
                    .isCloseTo(1.0 / 9, digits(9));
            assertThat(((Count.Direct) Count.COUNT_SYSTEMS.get("dtex")).factor())
                    .isCloseTo(0.1, digits(9));
            assertThat(constant("nm")).isEqualTo(1000);
        }

        @Test
        void worksTheCottonConstantOutToThePublished5905() {
            assertThat(constant("ne")).isCloseTo(590.54, digits(2));
        }

        @Test
        void worksTheWorstedLinenAndWoollenConstantsOutToo() {
            assertThat(constant("new")).isCloseTo(885.81, digits(2));
            assertThat(constant("nel")).isCloseTo(1653.52, digits(2));
            assertThat(constant("nw")).isCloseTo(1937.71, digits(2));
        }
    }

    @Nested
    class CountSystem {

        @Test
        void looksUpWhateverCaseTheCallerWrote() {
            assertThat(Count.countSystem("Tex").kind()).isEqualTo(Kind.DIRECT);
            assertThat(Count.countSystem("NE").kind()).isEqualTo(Kind.INDIRECT);
        }

        @Test
        void namesTheSystemsItDoesKnowWhenItRefusesOne() {
            InvalidArgumentException err =
                    catchThrowableOfType(InvalidArgumentException.class, () -> Count.countSystem("grist"));
            assertThat(err).isNotNull();
            assertThat(((JsonArray) err.detail().get("known")).items()).contains(Json.of("tex"));
        }
    }

    @Nested
    class ToTexAndFromTex {

        @Test
        void leavesTexAlone() {
            assertThat(Count.toTex(25, "tex")).isEqualTo(25);
            assertThat(Count.fromTex(25, "tex")).isEqualTo(25);
        }

        @Test
        void readsDecitexAsATenthOfATexAndDenierAsANinth() {
            assertThat(Count.toTex(250, "dtex")).isCloseTo(25, digits(10));
            assertThat(Count.toTex(225, "denier")).isCloseTo(25, digits(10));
            assertThat(Count.fromTex(25, "denier")).isCloseTo(225, digits(10));
        }

        @Test
        void invertsAnIndirectCount() {
            assertThat(Count.toTex(20, "ne")).isCloseTo(29.53, digits(2));
            assertThat(Count.toTex(50, "nm")).isCloseTo(20, digits(10));
        }

        @Test
        void roundTripsEverySystem() {
            for (String system : Count.COUNT_SYSTEMS.keySet()) {
                assertThat(Count.fromTex(Count.toTex(30, system), system)).isCloseTo(30, digits(8));
            }
        }

        @Test
        void refusesACountOfZeroWhichNoYarnHas() {
            assertThatThrownBy(() -> Count.toTex(0, "ne")).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class ConvertCount {

        @Test
        void turns30sCottonIntoItsMetricAndTexReadings() {
            assertThat(Count.convertCount(30, "ne", "tex", 2)).isEqualTo(19.68);
            assertThat(Count.convertCount(30, "ne", "nm", 2)).isEqualTo(50.8);
        }

        @Test
        void turns150DenierIntoDecitexWhichIsTheSameYarnTenTimesOver() {
            assertThat(Count.convertCount(150, "denier", "dtex", 2)).isEqualTo(166.67);
        }

        @Test
        void isItsOwnInverse() {
            double there = Count.convertCount(40, "ne", "nm", 10);
            assertThat(Count.convertCount(there, "nm", "ne", 6)).isCloseTo(40, digits(5));
        }
    }

    @Nested
    class IsIndirect {

        @Test
        void separatesTheTwoKindsOfSystem() {
            assertThat(Count.isIndirect("ne")).isTrue();
            assertThat(Count.isIndirect("nm")).isTrue();
            assertThat(Count.isIndirect("tex")).isFalse();
            assertThat(Count.isIndirect("denier")).isFalse();
        }
    }

    @Nested
    class FoldedCount {

        @Test
        void halvesAnIndirectCountWhenTwoEqualEndsAreFolded() {
            assertThat(Count.foldedCount(List.of(40, 40), "ne")).isEqualTo(20);
            assertThat(Count.foldedCount(List.of(60, 60, 60), "nm")).isEqualTo(20);
        }

        @Test
        void addsADirectCountInstead() {
            assertThat(Count.foldedCount(List.of(25, 25), "tex")).isEqualTo(50);
        }

        @Test
        void handlesUnequalEndsByReciprocals() {
            assertThat(Count.foldedCount(List.of(30, 60), "ne")).isEqualTo(20);
        }

        @Test
        void leavesASingleEndAlone() {
            assertThat(Count.foldedCount(List.of(40), "ne")).isEqualTo(40);
        }

        @Test
        void refusesAnEmptyFold() {
            assertThatThrownBy(() -> Count.foldedCount(List.of(), "ne")).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class FormatFold {

        @Test
        void writesTheTradeNotationForEqualEnds() {
            assertThat(Count.formatFold(List.of(40, 40), "ne")).isEqualTo("2/40s");
            assertThat(Count.formatFold(List.of(25, 25), "tex")).isEqualTo("2/25 tex");
        }

        @Test
        void listsUnequalEndsInstead() {
            assertThat(Count.formatFold(List.of(30, 60), "ne")).isEqualTo("30 + 60");
        }
    }

    @Nested
    class YarnDiameterInches {

        @Test
        void isOneOverTwentyEightRootNe() {
            assertThat(Count.yarnDiameterInches(16, "ne")).isCloseTo(1.0 / (28 * 4), digits(10));
        }

        @Test
        void getsFinerAsTheCountRises() {
            assertThat(Count.yarnDiameterInches(60, "ne")).isLessThan(Count.yarnDiameterInches(20, "ne"));
        }

        @Test
        void readsATexCountByConvertingFirst() {
            double fromNe = Count.yarnDiameterInches(30, "ne");
            double asTex = Count.yarnDiameterInches(Count.convertCount(30, "ne", "tex", 10), "tex");
            assertThat(asTex).isCloseTo(fromNe, digits(8));
        }

        @Test
        void takesADifferentDiameterConstantForADifferentFibre() {
            assertThat(Count.yarnDiameterInches(16, "ne", 25)).isCloseTo(1.0 / (25 * 4), digits(10));
        }
    }

    @Nested
    class ThreadsPerInch {

        @Test
        void isTheReciprocalOfTheDiameter() {
            assertThat(Count.threadsPerInch(16, "ne")).isEqualTo(112);
            assertThat(Count.threadsPerInch(36, "ne")).isEqualTo(168);
        }
    }

    @Nested
    class MetresPerGram {

        @Test
        void isTheMetricCountByDefinition() {
            assertThat(Count.metresPerGram(50, "nm")).isEqualTo(50);
        }

        @Test
        void agreesWithTheTexReading() {
            assertThat(Count.metresPerGram(20, "tex")).isEqualTo(50);
        }
    }
}
