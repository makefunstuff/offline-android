package com.lfmpixel.lfm.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps Inkling one look (docs/ux-spec.md, Daylight): colours, type sizes and corners come from the theme
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
            val text = line.substringBefore("//").trim()
            if (text.startsWith("*") || text.startsWith("/*")) null
            else if (rule.containsMatchIn(text)) "${f.rel()}:${i + 1}: $text" else null
        }
    }

    private fun none(what: String, hits: List<String>) =
        assertTrue("$what:\n" + hits.joinToString("\n"), hits.isEmpty())

    @Test fun foundTheCode() = assertTrue("no Kotlin found under $root", code.size > 50)

    @Test fun noRawColours() = none("A colour written out instead of a theme token (Lfm.c, CameraColors, the sky's colours)",
        find(code.filter { !it.inTheme() }, Regex("""\bColor\(\s*(0x|\d)|\bColor\.(White|Black|Red|Green|Blue|Yellow|Cyan|Magenta|Gray|DarkGray|LightGray)\b""")))

    @Test fun noRawTypeSizes() = none("A type size written out instead of an LfmType style",
        find(code.filter { !it.inTheme() }, Regex("""\b\d+(\.\d+)?\.sp\b|\bfontSize\s*=|\blineHeight\s*=""")))

    @Test fun noRawCorners() = none("A corner written out instead of an LfmShape",
        find(code.filter { !it.inTheme() }, Regex("""RoundedCornerShape\(\s*(\d|topStart|topEnd|bottomStart|bottomEnd|percent|size)""")))

    @Test fun stockMaterialOnlyInSharedComponents() = none(
        "A stock Material component on a screen: use the shared one (Btn, Switch, CheckBox, Card, Header, FlatSheet, Menu, DaySheet, SnackHost…)",
        find(code.filter { !it.inTheme() && !it.inComponents() }, Regex("""androidx\.compose\.material3\.(AlertDialog|BasicAlertDialog|Button|TextButton|""" +
            """OutlinedButton|FilledTonalButton|ElevatedButton|IconButton|FloatingActionButton|ExtendedFloatingActionButton|Card|ElevatedCard|OutlinedCard|""" +
            """Surface|Scaffold|TopAppBar|CenterAlignedTopAppBar|NavigationBar|NavigationBarItem|TextField|OutlinedTextField|Switch|Checkbox|RadioButton|""" +
            """Slider|RangeSlider|Snackbar|SnackbarHost|DropdownMenu|DropdownMenuItem|ModalBottomSheet|BottomSheetScaffold|DatePicker|DatePickerDialog|""" +
            """TimePicker|CircularProgressIndicator|LinearProgressIndicator|Tab|TabRow|FilterChip|AssistChip|SuggestionChip|InputChip|Badge|Divider|""" +
            """HorizontalDivider|VerticalDivider|Icon|ListItem|MaterialTheme)\b""")))

    @Test fun noPlatformDialogs() = none("A platform dialog or toast, drawn in the phone's look instead of Daylight's: use a FlatSheet, DaySheet or SnackHost",
        find(code, Regex("""android\.app\.(AlertDialog|DatePickerDialog|TimePickerDialog|ProgressDialog)|android\.widget\.Toast|\bToast\.makeText""")))

    @Test fun noOldTheme() = none("The old look: the organizer's tints (now the wash), or Material's own theme outside ui/theme",
        find(code, Regex("""\.(peach|lavender|mint)\b|\bon(Peach|Lavender|Mint)\b|\bc\.sky\b|\bSoftCard\b|\bSoftBtn\b""")) +
            find(code.filter { !it.inTheme() }, Regex("""\b(lightColorScheme|darkColorScheme|dynamicLightColorScheme|dynamicDarkColorScheme|Typography)\(""")))

    @Test fun widgetsUseTheirColours() {
        val res = File(root, "res").walkTopDown().filter { it.extension == "xml" && it.parentFile.name.startsWith("layout") }.toList()
        none("A colour written out in a widget's layout instead of @color/…", find(res, Regex("""android:(textColor|background|tint)="#""")))
    }
}
