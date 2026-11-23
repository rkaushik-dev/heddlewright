package io.github.rkaushikdev.heddlewright.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.heddlewright.cli.Registry.Command;
import io.github.rkaushikdev.heddlewright.draft.Draft;
import io.github.rkaushikdev.heddlewright.util.InvalidArgumentException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonArray;
import io.github.rkaushikdev.heddlewright.util.NotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CommandsTest {

    private static String run(String... argv) {
        Parse.Parsed parsed = Parse.parseArgs(List.of(argv));
        return Commands.commandByName(parsed.command().orElseThrow()).run(parsed);
    }

    /** What a command printed, with o for each raised end, which is how these tests write cloth. */
    private static String raised(String text) {
        return text.replace('X', 'o');
    }

    private static List<String> names() {
        return Commands.listCommands().stream().map(Command::name).toList();
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    @Nested
    class TheRegister {

        @Test
        void isFilledByLoadingTheCommandClassesRatherThanByAListHere() {
            assertThat(Commands.listCommands().size()).isGreaterThan(8);
        }

        @Test
        void holdsExactlyWhatTheTwoClassesSayTheyPutInIt() {
            List<String> declared = new ArrayList<>(Draw.DRAW_COMMANDS);
            declared.addAll(Shop.SHOP_COMMANDS);
            assertThat(names()).isEqualTo(declared.stream().sorted().toList());
        }

        @Test
        void holdsNoCommandTwice() {
            assertThat(new HashSet<>(names())).hasSameSizeAs(names());
        }

        @Test
        void givesEveryCommandASpecTheParserCanCheckAgainst() {
            for (Command command : Commands.listCommands()) {
                assertThat(Commands.specOf(command.name())).isEqualTo(command.spec());
            }
        }

        @Test
        void sharesOneOptionSetAcrossTheDrawingCommands() {
            for (String name : List.of("draft", "floats", "check", "report")) {
                assertThat(Commands.specOf(name)).containsAllEntriesOf(Commands.DRAW_OPTIONS);
            }
        }
    }

    @Nested
    class ListCommands {

        @Test
        void listsThemInNameOrderWithASummaryEach() {
            assertThat(names()).isSorted();
            assertThat(Commands.listCommands())
                    .allMatch(command -> !command.summary().isEmpty());
        }

        @Test
        void givesEveryCommandASpecObject() {
            assertThat(Commands.listCommands()).allMatch(command -> command.spec() != null);
        }
    }

    @Nested
    class CommandByName {

        @Test
        void findsACommandAndNamesTheRestWhenItCannot() {
            assertThat(Commands.commandByName("draft").name()).isEqualTo("draft");
            NotFoundException err =
                    catchThrowableOfType(NotFoundException.class, () -> Commands.commandByName("nothing"));
            assertThat(err).isNotNull();
            assertThat(((JsonArray) err.detail().get("known")).items()).contains(Json.of("draft"));
        }
    }

    @Nested
    class TheDraftCommand {

        @Test
        void printsTheSummaryAndTheCloth() {
            String text = run("draft", "--shafts", "4", "--ends", "8", "--picks", "4");
            assertThat(text).contains("shafts");
            assertThat(text).contains("XX..XX..");
        }

        @Test
        void drawsAPointThreadingWhenAsked() {
            List<String> rows = lines(run("draft", "--style", "point", "--shafts", "4", "--ends", "6", "--picks", "6"));
            assertThat(rows.get(rows.size() - 1)).hasSize(6);
        }

        @Test
        void followsTheTwillNotation() {
            assertThat(raised(run("draft", "--notation", "3/1", "--shafts", "4", "--ends", "4", "--picks", "4")))
                    .contains("ooo.");
        }

        @Test
        void refusesAnOptionItDoesNotDeclare() {
            assertThatThrownBy(() -> run("draft", "--shaftz", "4")).isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class TheFloatsCommand {

        @Test
        void reportsTheLongestFloatAndAHistogram() {
            String text = run("floats", "--notation", "3/1", "--shafts", "4");
            assertThat(text).contains("warp");
            assertThat(text).contains("direction");
        }

        @Test
        void readsAPlainTwillAsFloatingTwo() {
            assertThat(run("floats", "--notation", "2/2", "--shafts", "4")).containsPattern("warp\\s*:\\s*2");
        }
    }

    @Nested
    class TheWeaveCommand {

        @Test
        void printsATwillRepeat() {
            assertThat(run("weave", "--notation", "2/2")).isEqualTo("XX..\n.XX.\n..XX\nX..X");
        }

        @Test
        void printsASatinAndNamesItsLegalMoves() {
            String text = run("weave", "--kind", "satin", "--threads", "5", "--move", "2");
            assertThat(lines(text).get(0)).isEqualTo("satin 5 move 2, legal moves 2 3");
            assertThat(raised(text)).contains(".oooo");
        }

        @Test
        void refusesASatinMoveThatIsNotLegal() {
            assertThatThrownBy(() -> run("weave", "--kind", "satin", "--threads", "5", "--move", "1"))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    class TheSettCommand {

        @Test
        void putsBrierleysSettBesideAshenhurstsWhenAFamilyIsNamed() {
            String text = run("sett", "--count", "30", "--notation", "2/2", "--family", "twill");
            assertThat(text).containsPattern("brierley\\s*:\\s*102\\.59");
            assertThat(text).containsPattern("averageFloat\\s*:\\s*2");
            assertThat(run("sett", "--count", "30")).doesNotContain("brierley");
        }

        @Test
        void setsANamedCatalogueWeaveRatherThanATwill() {
            String satin = run("sett", "--count", "30", "--weave", "satin 5", "--family", "satin");
            assertThat(satin).containsPattern("brierley\\s*:\\s*105\\.67");
            assertThatThrownBy(() -> run("sett", "--count", "30", "--family", "crepe"))
                    .hasMessageContaining("family must be one of");
        }

        @Test
        void worksTheSettAndTheTwillAngleOut() {
            String text = run("sett", "--count", "30", "--notation", "2/2");
            assertThat(text).contains("warp");
            assertThat(text).contains("twillAngle");
            assertThat(text).containsPattern("twillAngle\\s*:\\s*45");
        }

        @Test
        void needsACount() {
            assertThatThrownBy(() -> run("sett")).hasMessageContaining("--count is required");
        }
    }

    @Nested
    class TheCountCommand {

        @Test
        void convertsBetweenSystems() {
            assertThat(run("count", "--value", "30", "--from", "ne", "--to", "tex"))
                    .isEqualTo("30 ne = 19.6847 tex");
        }

        @Test
        void refusesASystemItDoesNotKnow() {
            assertThatThrownBy(() -> run("count", "--value", "30", "--from", "grist"))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    @Nested
    class TheSystemsCommand {

        @Test
        void listsEveryCountSystemWithItsKind() {
            String text = run("systems");
            assertThat(text).contains("tex");
            assertThat(text).contains("indirect");
            assertThat(lines(text).size()).isGreaterThan(8);
        }
    }

    @Nested
    class TheCheckCommand {

        @Test
        void saysSoWhenThereIsNothingWrong() {
            assertThat(run("check", "--shafts", "4", "--ends", "8")).isEqualTo("nothing wrong with it");
        }

        @Test
        void reportsAnIdleShaft() {
            assertThat(run("check", "--shafts", "8", "--ends", "4", "--picks", "8"))
                    .contains("idle_shaft");
        }
    }

    @Nested
    class TheReportCommand {

        @Test
        void printsEverySectionOfTheClothReport() {
            String text = run("report", "--shafts", "4", "--ends", "8", "--picks", "8");
            assertThat(text).contains("Structure");
            assertThat(text).contains("Floats");
            assertThat(text).contains("Sett");
        }

        @Test
        void readsTheRepeatOutOfTheDrawnCloth() {
            assertThat(run("report", "--shafts", "4", "--ends", "16", "--picks", "16"))
                    .containsPattern("repeatEnds\\s*:\\s*4");
        }

        @Test
        void takesAFloatLimitAndAYarnCount() {
            assertThat(run("report", "--floatLimit", "1", "--count", "40")).containsPattern("floatLimit\\s*:\\s*1");
        }
    }

    @Nested
    class TheCatalogueCommand {

        @Test
        void listsEveryWeaveMeasured() {
            String text = run("catalogue");
            assertThat(text).contains("plain");
            assertThat(text).contains("satin 8");
            assertThat(text).contains("warp face");
        }

        @Test
        void filtersToOneFamily() {
            String text = run("catalogue", "--family", "satin");
            assertThat(text).contains("satin 5");
            assertThat(text).doesNotContain("plain ");
        }

        @Test
        void saysWhichFamiliesThereAreWhenItFindsNone() {
            assertThat(run("catalogue", "--family", "gauze")).contains("families:");
        }
    }

    @Nested
    class TheDeckCommand {

        @Test
        void cutsADeckForANamedWeaveAndPricesIt() {
            String text = run("deck", "--weave", "twill 2/2");
            assertThat(text).contains("machine");
            assertThat(text).containsPattern("cards\\s*:\\s*4");
            assertThat(text).containsPattern("poundsAndPence\\s*:\\s*0\\.");
        }

        @Test
        void repeatsTheWeaveWhenAsked() {
            String text = run("deck", "--weave", "twill 2/2", "--repeats", "3");
            assertThat(text).containsPattern("cards\\s*:\\s*12");
            assertThat(text).containsPattern("repeat\\s*:\\s*4");
        }

        @Test
        void refusesAWeaveTheCatalogueDoesNotHave() {
            assertThatThrownBy(() -> run("deck", "--weave", "gauze")).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    class Usage {

        @Test
        void describesOneCommandFromTheSameSpecTheParserChecks() {
            String text = Commands.commandUsage("count");
            assertThat(text).contains("--value <number> (required)");
            assertThat(text).contains("--from");
        }

        @Test
        void describesTheWholeTool() {
            String text = Commands.toolUsage();
            assertThat(lines(text).get(0)).isEqualTo("heddle <command> [options]");
            for (Command command : Commands.listCommands()) {
                assertThat(text).contains(command.name());
            }
        }
    }

    @Nested
    class BuildDraft {

        @Test
        void clampsATwillRaiseThatWouldLiftEveryShaft() {
            Draft draft = Commands.buildDraft(
                    Json.obj("style", "twill", "shafts", 4, "ends", 8, "picks", 8, "notation", "9/1"));
            assertThat(draft.tieup().get(0)).hasSize(3);
        }
    }

    @Nested
    class TheBoxesCommand {

        @Test
        void allotsForTheShortestTravelWhenAsked() {
            String order = "red red red red red red gold green gold green gold gold red red blue blue blue red";
            String byCount = run("boxes", "--pattern", order, "--boxes", "4");
            String byTravel = run("boxes", "--pattern", order, "--boxes", "4", "--allot", "travel");
            assertThat(byCount).containsPattern("travel\\s*:\\s*14").contains("2:gold");
            assertThat(byTravel).containsPattern("travel\\s*:\\s*10").contains("2:blue");
            assertThatThrownBy(() -> run("boxes", "--pattern", order, "--allot", "nearest"))
                    .hasMessageContaining("allot must be one of");
        }

        @Test
        void gaitsAWeftOrderUpAndPrintsTheChainToLace() {
            String text = run("boxes", "--pattern", "ground ground blue blue", "--boxes", "2");
            assertThat(text).containsPattern("picks\\s*:\\s*4");
            assertThat(text).containsPattern("chain\\s*:\\s*1 1 2 2");
            assertThat(text).contains("1:ground");
        }

        @Test
        void takesTheOrderWrittenWithCommasTheWayAPatternCardIs() {
            assertThat(run("boxes", "--pattern", "red,red,blue,blue", "--boxes", "2"))
                    .containsPattern("colours\\s*:\\s*2");
        }

        @Test
        void refusesAnOrderThatWillNotPairOnALoomBoxedAtOneEnd() {
            assertThatThrownBy(() -> run("boxes", "--pattern", "red blue blue", "--boxes", "2", "--boxedAt", "one"))
                    .hasMessageContaining("picked in pairs");
        }

        @Test
        void refusesMoreWeftsThanTheLoomHasBoxes() {
            assertThatThrownBy(() -> run("boxes", "--pattern", "a a b b c c", "--boxes", "2"))
                    .hasMessageContaining("will not go");
        }
    }
}
