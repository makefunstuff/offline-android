package com.lfmpixel.lfm.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps Inkling one look (docs/ux-spec.md, Horizon Almanac): colours, type sizes and corners come from the theme
 * (ui/theme), stock Material pieces only through the shared components (ui/components), and nothing of the old look
 * (the organizer's tints, Material's default theme, the platform's dialogs) comes back. Each failure names the file,
 * the line and what to use instead.
 */
class DaylightGuardTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val code = File(root, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun File.rel() = relativeTo(File(root, "java/com/lfmpixel/lfm")).invariantSeparatorsPath
    private fun File.inTheme() = rel().startsWith("ui/theme/")
    private fun File.inComponents() = rel().startsWith("ui/components/")

    /** Every line of [files] matching [rule], minus comments, as "file:line: text". */
    private fun find(files: List<File>, rule: Regex): List<String> = files.flatMap { f ->
        f.readLines().mapIndexedNotNull { i, line ->
            // A comment's text is not code; "https://" in a string is not a comment.
            val text = line.replace(Regex("""(?<!:)//.*$"""), "").trim()
            if (text.startsWith("*") || text.startsWith("/*")) null
            else if (rule.containsMatchIn(text)) "${f.rel()}:${i + 1}: $text" else null
        }
    }

    private fun none(what: String, hits: List<String>) =
        assertTrue("$what:\n" + hits.joinToString("\n"), hits.isEmpty())

    @Test fun foundTheCode() = assertTrue("no Kotlin found under $root", code.size > 50)

    @Test fun noRawColours() = none("A colour written out instead of a theme token (Lfm.c, CameraColors, the sky's colours)",
        find(code.filter { !it.inTheme() }, Regex("""\bColor\(\s*(0x|\d|red\s*=|alpha\s*=)|\bColor\.(White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray)\b|""" +
            """android\.graphics\.Color\.([A-Z]|rgb|argb|parseColor)|\bColor\.(hsl|hsv)\(|\bColor\.Companion\.|\bColor\(\s*color\s*=""")))

    /** An opaque ARGB written out (0xFF…) anywhere but the theme and the sky's own palette (daylight/Sky.kt). */
    @Test fun noRawArgb() = none("An ARGB colour written out instead of a theme or sky token",
        find(code.filter { !it.inTheme() && it.rel() != "daylight/Sky.kt" }, Regex("""\b0x[Ff]{2}[0-9A-Fa-f]{6}\b""")))

    @Test fun noRawTypeSizes() = none("A type size written out instead of an LfmType style",
        find(code.filter { !it.inTheme() }, Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*=|\blineHeight\s*=""")))

    @Test fun noRawCorners() = none("A corner written out instead of an LfmShape",
        find(code.filter { !it.inTheme() }, Regex("""\b(RoundedCornerShape|CutCornerShape|AbsoluteRoundedCornerShape|AbsoluteCutCornerShape)\(|\bCircleShape\b""")))

    @Test fun stockMaterialOnlyInSharedComponents() = none(
        "Material used on a screen: use the shared piece (Txt, RichTxt, Btn, Switch, CheckBox, Card, Header, FlatSheet, Menu, DaySheet, SnackHost…)",
        find(code.filter { !it.inTheme() && !it.inComponents() },
            Regex("""androidx\.compose\.material3?\.(?!ExperimentalMaterial3Api\b)[A-Za-z*]|\bMaterialTheme\b""")))

    /** Horizon Almanac has no outlined boxes: groups sit on the wash, buttons are words (the camera keeps its own look). */
    @Test fun noOutlinedBoxes() = none("An outlined box on a screen: put the group on the wash (background(c.wash, …)) or use a Btn, which is a word",
        find(code.filter { !it.inTheme() && !it.inComponents() && it.name != "CameraScreen.kt" },
            Regex("""\.border\(\s*1\.dp,\s*(Lfm\.)?c\.(line|control|text)\s*,\s*(LfmShape\.(card|control)|shape|fieldShape)\)""")))

    @Test fun noPlatformDialogs() = none("A platform dialog or toast, drawn in the phone's look instead of Daylight's: use a FlatSheet, DaySheet or SnackHost",
        find(code, Regex("""android\.app\.(AlertDialog|DatePickerDialog|TimePickerDialog|ProgressDialog)|android\.widget\.Toast|\bToast\.makeText""")))

    @Test fun noOldTheme() = none("The old look: the organizer's tints (now the wash), or Material's own theme outside ui/theme",
        find(code, Regex("""\.(peach|lavender|mint)\b|\bon(Peach|Lavender|Mint)\b|\bc\.sky\b|\bSoftCard\b|\bSoftBtn\b""")) +
            find(code.filter { !it.inTheme() }, Regex("""\b(lightColorScheme|darkColorScheme|dynamicLightColorScheme|dynamicDarkColorScheme|Typography)\(""")))

    /** The widgets' layouts and their drawables (w_*, ic_w_*) take their colours from the values colors.xml files. */
    @Test fun widgetsUseTheirColours() {
        val res = File(root, "res").walkTopDown().filter { f -> f.extension == "xml" &&
            (f.parentFile.name.startsWith("layout") || (f.parentFile.name.startsWith("drawable") && (f.name.startsWith("w_") || f.name.startsWith("ic_w_")))) }.toList()
        none("A colour written out in a widget's layout or drawable instead of @color/…",
            find(res, Regex("""android:\w*([Cc]olor|background|[Tt]int)="#(?!00000000")""")))
    }
}
