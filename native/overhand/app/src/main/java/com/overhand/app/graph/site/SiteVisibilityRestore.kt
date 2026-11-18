package com.overhand.app.graph.site

/** Preserve intended child visibility until a budgeted show has actually restored it.
 * A second hide during that show must not capture its temporary, partly hidden state. */
class SiteVisibilityRestore<T> {
  private val saved=LinkedHashMap<T,Boolean>()
  fun hide(entities: Iterable<T>,current: (T) -> Boolean,write: (T,Boolean) -> Unit) {
    entities.forEach { e -> saved.getOrPut(e) { current(e) };write(e,false) }
  }
  fun resume(write: (T,Boolean) -> Unit) { saved.forEach { (e,on) -> write(e,on) } }
  /** Called only while showing, after all visibility writes have drained. */
  fun settled() { saved.clear() }
  fun clear() { saved.clear() }
}
