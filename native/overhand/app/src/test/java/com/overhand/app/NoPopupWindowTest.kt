package com.overhand.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A panel's window context cannot host a Dialog: Spinner's dropdown crashed the app on the headset
 * (runs/native/session_nov12/worn/logcat_crash2.txt, "Window type mismatch ... 2037 ... 2"). No source under
 * src/main may reference a class that opens its own window; pages in panels may not open native pickers or dialogs.
 */
class NoPopupWindowTest {
  private val main=File("src/main")
  /** "path: Name" entries that are allowed anyway. Empty: nothing needs one today. */
  private val allow=emptySet<String>()
  private val classes=Regex("""\b(Spinner|AppCompatSpinner|AbsSpinner|Dialog|AlertDialog|AppCompatDialog|DialogFragment|BottomSheetDialog|ProgressDialog|""" +
    """DatePicker|DatePickerDialog|TimePicker|TimePickerDialog|NumberPicker|CalendarView|PopupWindow|ListPopupWindow|PopupMenu|""" +
    """AutoCompleteTextView|MultiAutoCompleteTextView|ExposedDropdownMenu|DropdownMenu|showDialog|setSupportMultipleWindows)\b""")
  private val web=Regex("""<select\b|<dialog\b|\.showModal\s*\(|(?<![\w.])(window\.)?(alert|confirm|prompt)\s*\(|type\s*=\s*["']?(date|time|datetime-local|month|week|color)\b""")
  private fun code(text: String)=text.replace(Regex("""/\*[\s\S]*?\*/"""),"").replace(Regex("""//[^\n]*"""),"")
  fun refs(): List<String> {
    val out=mutableListOf<String>()
    main.walkTopDown().filter { it.isFile }.forEach { f ->
      val rel=f.relativeTo(main).invariantSeparatorsPath
      val rule=when(f.extension) { "kt","java" -> classes; "xml" -> classes; "js","html","htm" -> web; else -> null } ?: return@forEach
      val body=if(f.extension in listOf("kt","java","js")) code(f.readText()) else f.readText()
      rule.findAll(body).forEach { m -> "$rel: ${m.value}".let { if(it !in allow) out.add(it) } }
    }
    return out
  }
  @Test fun noSourceReferencesAWindowOpeningClass() {
    val found=refs()
    println("dialog_class_refs=${found.size}")
    assertEquals("Use in-panel controls (windows/ChipRow.kt), never a popup window:\n"+found.joinToString("\n"),0,found.size)
  }
  @Test fun theScanCatchesTheControlsThatCrashed() {
    val probe="val picker=Spinner(ctx) // Dialog in a comment is fine\nAlertDialog.Builder(ctx)"
    assertEquals(listOf("Spinner","AlertDialog"),classes.findAll(code(probe)).map { it.value }.toList())
    assertEquals(3,web.findAll("<select id=a> alert('x'); window.confirm('y'); obj.prompt(1)").count())
  }
}
