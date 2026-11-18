package com.overhand.app.windows

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Every clickable view under a panel root, in tree order. Debug smoke (tap_every_control) only. */
object ControlWalker {
  fun descendants(root: View): List<View> = listOf(root)+(if(root is ViewGroup)
    (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList())
  fun clickable(root: View): List<View> = descendants(root).filter { it.isClickable || it.hasOnClickListeners() }
  fun label(v: View): String = (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
    ?: v.contentDescription?.toString().orEmpty()
  fun idName(v: View): String = if(v.id==View.NO_ID) "" else try { v.resources.getResourceEntryName(v.id) } catch(e: Exception) { v.id.toString() }
  fun describe(panel: String,root: View): JSONArray {
    val out=JSONArray()
    val origin=IntArray(2);root.getLocationInWindow(origin)
    clickable(root).forEachIndexed { i,v ->
      val p=IntArray(2);v.getLocationInWindow(p)
      out.put(JSONObject().put("view",panel).put("index",i).put("id",idName(v)).put("tag",v.tag?.toString() ?: "")
        .put("text",label(v)).put("class",v.javaClass.simpleName).put("enabled",v.isEnabled).put("shown",v.isShown)
        .put("bounds",JSONArray(listOf(p[0]-origin[0],p[1]-origin[1],p[0]-origin[0]+v.width,p[1]-origin[1]+v.height))))
    }
    return out
  }
  /** Clicks control [index] if its label still reads [text] (the tree can change between taps); else the first with that label. */
  fun click(root: View,index: Int,text: String?): View? {
    val all=clickable(root)
    val target=all.getOrNull(index)?.takeIf { text==null || label(it)==text } ?: all.firstOrNull { text!=null && label(it)==text }
    target?.performClick()
    return target
  }
}
