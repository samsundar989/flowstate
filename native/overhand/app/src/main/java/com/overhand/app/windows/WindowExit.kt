package com.overhand.app.windows

import android.app.Activity
import android.widget.*
import android.view.View

/** Persistent footer: explicit hands-only exit with a separate confirmation target. */
object WindowExit {
  fun attach(root: LinearLayout,activity: Activity) {
    val footer=LinearLayout(root.context)
    val quit=Button(root.context).apply { text="Quit Flowstate" }
    val confirm=Button(root.context).apply { text="Confirm quit";visibility=View.GONE }
    quit.setOnClickListener {
      confirm.visibility=if(confirm.visibility==View.VISIBLE) View.GONE else View.VISIBLE
      quit.text=if(confirm.visibility==View.VISIBLE) "Keep working" else "Quit Flowstate"
    }
    confirm.setOnClickListener { activity.finishAndRemoveTask() }
    footer.addView(quit,LinearLayout.LayoutParams(0,-2,1f))
    footer.addView(confirm,LinearLayout.LayoutParams(0,-2,1f))
    root.addView(footer)
  }
}
