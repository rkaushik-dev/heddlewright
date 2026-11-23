package io.github.rkaushikdev.heddlewright.draft;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.Matrix;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writing a weaving interchange file.
 *
 * <p>The contents section is the part that matters and the part most writers get wrong: it
 * promises what the file holds, and a promise the file does not keep makes every reader guess.
 * Here the contents are worked out from the draft rather than declared by the caller, so the two
 * cannot drift apart.
 *
 * <p>A dobby draft carries a liftplan where a treadle loom carries a tie-up and a treadling, and the
 * colours of the warp and the weft go in a palette with each thread pointing into it. Both are
 * decided while the sections are built, so a section that is written is a section that is declared.
 */
public final class WifWrite {

    /**
     * Who wrote the file, any notes to put in place of the draft's own, the date to stamp, and the
     * colour of each end and each pick, as {@code #rrggbb}, when they are known.
     */
    public record Options(
            String author, String notes, Optional<String> date, List<String> warpColours, List<String> weftColours) {

        /** No author, the draft's own notes, an unspecified date and no colours. */
        public static final Options DEFAULT = new Options("", "", Optional.empty(), List.of(), List.of());

        /** Copies the colours. */
        public Options {
            warpColours = List.copyOf(warpColours);
            weftColours = List.copyOf(weftColours);
        }

        /** The same options with an author. */
        public Options author(String name) {
            return new Options(name, notes, date, warpColours, weftColours);
        }

        /** The same options with notes. */
        public Options notes(String text) {
            return new Options(author, text, date, warpColours, weftColours);
        }

        /** The same options with a date. */
        public Options date(String day) {
            return new Options(author, notes, Optional.of(day), warpColours, weftColours);
        }

        /** The same options with a colour for every end and every pick. */
        public Options colours(List<String> warp, List<String> weft) {
            return new Options(author, notes, date, warp, weft);
        }
    }

    private static final String PROGRAM = "heddlewright";

    private WifWrite() {}

    /** Write a draft out as a weaving file. */
    public static String writeWif(Draft draft) {
        return writeWif(draft, Options.DEFAULT);
    }

    /** Write a draft out as a weaving file, with an author, notes, a date or colours. */
    public static String writeWif(Draft draft, Options options) {
        return Ini.formatIni(sections(draft, Optional.empty(), options));
    }

    /** Write a dobby draft with its liftplan in place of the tie-up and treadling. */
    public static String writeDobbyWif(Draft draft, Matrix liftplan) {
        return writeDobbyWif(draft, liftplan, Options.DEFAULT);
    }

    /**
     * Write a dobby draft with a liftplan instead of a tie-up and treadling, which is what a loom
     * with no treadles wants to read.
     */
    public static String writeDobbyWif(Draft draft, Matrix liftplan, Options options) {
        Check.object(liftplan, "liftplan");
        return Ini.formatIni(sections(draft, Optional.of(liftplan), options));
    }

    /** The sections a written treadle file will carry, for a caller checking before writing. */
    public static List<String> contentsFor(Draft draft) {
        return List.copyOf(sections(draft, Optional.empty(), Options.DEFAULT)
                .get("CONTENTS")
                .keySet());
    }

    /**
     * Every section of the file in the order it is written. The contents are filled in last from the
     * sections that were actually built, and sit second in the file.
     */
    private static Map<String, Map<String, String>> sections(Draft draft, Optional<Matrix> liftplan, Options options) {
        Check.object(draft, "draft");
        Map<String, Map<String, String>> body = new LinkedHashMap<>();
        body.put(
                "TEXT",
                ordered(
                        "Title", draft.name(),
                        "Author", options.author(),
                        "Notes", Text.trim(options.notes()).isEmpty() ? draft.notes() : options.notes()));
        body.put(
                "WEAVING",
                ordered(
                        "Shafts", String.valueOf(draft.shafts()),
                        "Treadles", String.valueOf(draft.treadles()),
                        "Rising Shed", draft.shed() == Shed.RISING ? "yes" : "no"));
        body.put("WARP", ordered("Threads", String.valueOf(draft.threading().size())));
        body.put("WEFT", ordered("Threads", String.valueOf(draft.treadling().size())));
        body.put("THREADING", numbered(draft.threading(), String::valueOf));
        if (liftplan.isPresent()) {
            body.put("LIFTPLAN", numbered(liftplan.get().allRows(), WifWrite::lifted));
        } else {
            if (!draft.tieup().isEmpty()) {
                body.put("TIEUP", numbered(draft.tieup(), WifWrite::joined));
            }
            if (!draft.treadling().isEmpty()) {
                body.put("TREADLING", numbered(draft.treadling(), WifWrite::joined));
            }
        }
        colours(body, draft, options);
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        sections.put(
                "WIF",
                ordered(
                        "Version",
                        Wif.WIF_VERSION,
                        "Date",
                        options.date().orElse("unspecified"),
                        "Developers",
                        PROGRAM,
                        "Source Program",
                        PROGRAM));
        Map<String, String> contents = new LinkedHashMap<>();
        body.keySet().forEach(name -> contents.put(name, "yes"));
        sections.put("CONTENTS", contents);
        sections.putAll(body);
        return sections;
    }

    /** The palette and the thread colours, when the options give a colour for every thread. */
    private static void colours(Map<String, Map<String, String>> body, Draft draft, Options options) {
        if (options.warpColours().isEmpty() && options.weftColours().isEmpty()) {
            return;
        }
        Check.array(
                options.warpColours(),
                "warpColours",
                draft.threading().size(),
                draft.threading().size());
        Check.array(
                options.weftColours(),
                "weftColours",
                draft.treadling().size(),
                draft.treadling().size());
        List<String> palette = new ArrayList<>();
        for (String colour : concat(options.warpColours(), options.weftColours())) {
            if (!palette.contains(colour.toLowerCase(Locale.ROOT))) {
                palette.add(colour.toLowerCase(Locale.ROOT));
            }
        }
        body.put("COLOR PALETTE", ordered("Entries", String.valueOf(palette.size()), "Range", "0,255"));
        body.put("COLOR TABLE", numbered(palette, colour -> Wif.rgbOf(colour, "colour")));
        Function<String, String> index = colour -> String.valueOf(palette.indexOf(colour.toLowerCase(Locale.ROOT)) + 1);
        body.put("WARP COLORS", numbered(options.warpColours(), index));
        body.put("WEFT COLORS", numbered(options.weftColours(), index));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>(first);
        out.addAll(second);
        return out;
    }

    private static Map<String, String> ordered(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static <T> Map<String, String> numbered(List<T> values, Function<T, String> write) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index += 1) {
            map.put(String.valueOf(index + 1), write.apply(values.get(index)));
        }
        return map;
    }

    private static String joined(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** The shafts a liftplan row raises, counted from one. */
    private static String lifted(List<Integer> row) {
        List<String> shafts = new ArrayList<>();
        for (int shaft = 0; shaft < row.size(); shaft += 1) {
            if (row.get(shaft) != 0) {
                shafts.add(String.valueOf(shaft + 1));
            }
        }
        return String.join(",", shafts);
    }
}
