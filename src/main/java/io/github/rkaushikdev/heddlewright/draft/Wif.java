package io.github.rkaushikdev.heddlewright.draft;

import io.github.rkaushikdev.heddlewright.util.Check;
import io.github.rkaushikdev.heddlewright.util.DraftException;
import io.github.rkaushikdev.heddlewright.util.Json;
import io.github.rkaushikdev.heddlewright.util.JsonObject;
import io.github.rkaushikdev.heddlewright.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Reading a weaving interchange file.
 *
 * <p>The format keeps the draft in four numbered sections: the threading by end, the tie-up by
 * treadle, the treadling by pick and, on a dobby draft, a liftplan by pick instead of the last two.
 * What it does not keep is any guarantee that those agree with the shaft and treadle counts in the
 * header, so everything is checked on the way in rather than trusted.
 *
 * <p>Colours come in a palette and an index per thread. The palette's range is whatever the writing
 * program chose, 0 to 255 or 0 to 999 or 0 to 65535, so every entry is scaled to 0 to 255 before it
 * is read as {@code #rrggbb}. A thread the colour sections leave out takes the default colour its
 * own section names.
 */
public final class Wif {

    /** The version of the format this package writes. */
    public static final String WIF_VERSION = "1.1";

    /** What a file declares, read before any draft is built from it. */
    public record Read(
            String version,
            String program,
            String title,
            String author,
            int shafts,
            OptionalInt treadles,
            boolean risingShed,
            int warpThreads,
            int weftThreads,
            List<Integer> threading,
            List<List<Integer>> tieup,
            List<List<Integer>> treadling,
            List<List<Integer>> liftplan,
            boolean hasTieup,
            boolean hasLiftplan) {

        /** Copies the lists. */
        public Read {
            threading = List.copyOf(threading);
            tieup = List.copyOf(tieup);
            treadling = List.copyOf(treadling);
            liftplan = List.copyOf(liftplan);
        }
    }

    /** The colours a file carries: its palette as {@code #rrggbb}, and the colour of each end and pick. */
    public record Colours(List<String> palette, List<String> warp, List<String> weft) {
        /** Copies the lists. */
        public Colours {
            palette = List.copyOf(palette);
            warp = List.copyOf(warp);
            weft = List.copyOf(weft);
        }
    }

    private static final Pattern HEX = Pattern.compile("^#[0-9a-f]{6}$");

    private Wif() {}

    /** A {@code #rrggbb} colour as the {@code r,g,b} a colour table holds, refusing anything else. */
    static String rgbOf(String hex, String field) {
        String colour = Text.trim(Check.nonEmptyString(hex, field)).toLowerCase(java.util.Locale.ROOT);
        if (!HEX.matcher(colour).matches()) {
            throw new DraftException(field + " is not a #rrggbb colour", Json.obj("field", field, "got", hex));
        }
        return Integer.parseInt(colour.substring(1, 3), 16) + "," + Integer.parseInt(colour.substring(3, 5), 16) + ","
                + Integer.parseInt(colour.substring(5, 7), 16);
    }

    /**
     * Read the colours a file carries, or nothing in every list when it has no colour table. A thread
     * with no colour of its own and no default, or an index the palette does not hold, is refused.
     */
    public static Colours readColours(String text) {
        Map<String, Map<String, String>> sections = Ini.parseIni(text);
        Map<String, String> table = Ini.section(sections, "COLOR TABLE");
        if (table.isEmpty()) {
            return new Colours(List.of(), List.of(), List.of());
        }
        List<Integer> range = Ini.intListValue(sections, "COLOR PALETTE", "Range");
        int top = range.size() == 2 ? range.get(1) : 255;
        int entries = Ini.intValue(sections, "COLOR PALETTE", "Entries", table.size());
        List<String> palette = new ArrayList<>(entries);
        for (int index = 1; index <= entries; index += 1) {
            List<Integer> rgb = Ini.intListValue(sections, "COLOR TABLE", String.valueOf(index));
            if (rgb.size() != 3) {
                throw new DraftException(
                        "colour " + index + " in the table is not three numbers", Json.obj("colour", index));
            }
            StringBuilder hex = new StringBuilder("#");
            rgb.forEach(part -> hex.append(Text.padStart(
                    Integer.toHexString((int) Math.round(Math.min(top, Math.max(0, part)) * 255.0 / top)), 2, '0')));
            palette.add(hex.toString());
        }
        return new Colours(palette, threadColours(sections, "WARP", palette), threadColours(sections, "WEFT", palette));
    }

    private static List<String> threadColours(
            Map<String, Map<String, String>> sections, String side, List<String> palette) {
        int threads = Ini.intValue(sections, side, "Threads", 0);
        int fallback = Ini.intValue(sections, side, "Color", 0);
        List<Integer> indexes = Ini.numberedList(sections, side + " COLORS", threads, fallback);
        List<String> out = new ArrayList<>(threads);
        for (int thread = 0; thread < threads; thread += 1) {
            int index = indexes.get(thread);
            if (index < 1 || index > palette.size()) {
                throw new DraftException(
                        side.toLowerCase(java.util.Locale.ROOT) + " thread " + (thread + 1)
                                + " has no colour in the palette",
                        Json.obj("thread", thread + 1, "colour", index, "palette", palette.size()));
            }
            out.add(palette.get(index - 1));
        }
        return List.copyOf(out);
    }

    /** Read a file into the pieces it declares, before any draft is built. */
    public static Read readWif(String text) {
        Map<String, Map<String, String>> sections = Ini.parseIni(text);
        if (Ini.section(sections, "WIF").isEmpty()) {
            throw new DraftException("this file has no WIF section, so it is not a weaving file", JsonObject.EMPTY);
        }
        OptionalInt shafts = Ini.intValue(sections, "WEAVING", "Shafts");
        OptionalInt treadles = Ini.intValue(sections, "WEAVING", "Treadles");
        if (shafts.isEmpty()) {
            throw new DraftException("the file does not say how many shafts the draft needs", JsonObject.EMPTY);
        }
        int warpThreads = Ini.intValue(sections, "WARP", "Threads", 0);
        int weftThreads = Ini.intValue(sections, "WEFT", "Threads", 0);
        return new Read(
                Ini.value(sections, "WIF", "Version", ""),
                Ini.value(sections, "WIF", "Source Program", ""),
                Ini.value(sections, "TEXT", "Title", ""),
                Ini.value(sections, "TEXT", "Author", ""),
                shafts.getAsInt(),
                treadles,
                Ini.boolValue(sections, "WEAVING", "Rising Shed", true),
                warpThreads,
                weftThreads,
                Ini.numberedList(sections, "THREADING", warpThreads, 0),
                treadles.isEmpty() ? List.of() : Ini.numberedLists(sections, "TIEUP", treadles.getAsInt()),
                Ini.numberedLists(sections, "TREADLING", weftThreads),
                Ini.numberedLists(sections, "LIFTPLAN", weftThreads),
                !Ini.section(sections, "TIEUP").isEmpty(),
                !Ini.section(sections, "LIFTPLAN").isEmpty());
    }

    /**
     * A weaving file writes an absent title as an empty string as often as it leaves the key out, so
     * a plain fallback picks the empty one up and hands it on to a check that refuses it.
     */
    private static String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !Text.trim(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "untitled";
    }

    /** Turn a file into a draft named by its title, checking everything the header promised. */
    public static Draft draftFromWif(String text) {
        return draftFromWif(text, null);
    }

    /** Turn a file into a draft under the given name, checking everything the header promised. */
    public static Draft draftFromWif(String text, String name) {
        Read read = readWif(text);
        if (read.warpThreads() == 0) {
            throw new DraftException("the file does not say how many warp threads it has", JsonObject.EMPTY);
        }
        List<Integer> missing = new ArrayList<>();
        for (int index = 0; index < read.threading().size(); index += 1) {
            if (read.threading().get(index) == 0) {
                missing.add(index + 1);
            }
        }
        if (!missing.isEmpty()) {
            throw new DraftException(
                    "the threading has ends with no shaft",
                    Json.obj("ends", missing.subList(0, Math.min(10, missing.size())), "missing", missing.size()));
        }
        if (read.hasLiftplan()) {
            return draftFromLiftplanFile(read, name);
        }
        if (read.treadles().isEmpty() || !read.hasTieup()) {
            throw new DraftException("the file has neither a tie-up nor a liftplan", JsonObject.EMPTY);
        }
        return Draft.create(Json.obj(
                "name", firstNonEmpty(name, read.title()),
                "shafts", read.shafts(),
                "treadles", read.treadles().getAsInt(),
                "shed", read.risingShed() ? Shed.RISING : Shed.SINKING,
                "threading", read.threading(),
                "tieup", read.tieup(),
                "treadling", read.treadling(),
                "notes", read.author().isEmpty() ? "" : "after " + read.author()));
    }

    /**
     * A dobby file carries a liftplan instead of a tie-up. Each distinct lift becomes a treadle, in
     * the order the picks first use it.
     */
    private static Draft draftFromLiftplanFile(Read read, String name) {
        Map<List<Integer>, Integer> seen = new LinkedHashMap<>();
        List<List<Integer>> tieup = new ArrayList<>();
        List<List<Integer>> treadling = new ArrayList<>();
        for (List<Integer> lift : read.liftplan()) {
            List<Integer> sorted = lift.stream().sorted().toList();
            Integer treadle = seen.get(sorted);
            if (treadle == null) {
                treadle = tieup.size() + 1;
                seen.put(sorted, treadle);
                tieup.add(sorted);
            }
            treadling.add(List.of(treadle));
        }
        return Draft.create(Json.obj(
                "name", firstNonEmpty(name, read.title()),
                "shafts", read.shafts(),
                "treadles", tieup.size(),
                "shed", read.risingShed() ? Shed.RISING : Shed.SINKING,
                "threading", read.threading(),
                "tieup", tieup,
                "treadling", treadling));
    }

    /** What a file declares it carries, without reading any of it. */
    public static List<String> wifContents(String text) {
        Map<String, Map<String, String>> sections = Ini.parseIni(text);
        List<String> carried = new ArrayList<>();
        for (Map.Entry<String, String> entry : Ini.section(sections, "CONTENTS").entrySet()) {
            if (Ini.isYes(entry.getValue())) {
                carried.add(entry.getKey());
            }
        }
        carried.sort(null);
        return List.copyOf(carried);
    }

    /** True when the file says it carries the section, and actually does. */
    public static boolean declaresAndCarries(String text, String name) {
        Check.nonEmptyString(name, "name");
        Map<String, Map<String, String>> sections = Ini.parseIni(text);
        return Ini.boolValue(sections, "CONTENTS", name, false)
                && !Ini.section(sections, name).isEmpty();
    }
}
