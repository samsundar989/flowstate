package com.overhand.app.places

/**
 * Where the wearer works: a baked sky (Moon, or the photographs Mountain and Beach), the plain virtual space,
 * or passthrough (My room). The declaration order is the cycle order.
 */
enum class Place(val label: String) {
  MOON("Moon"), MOUNTAIN("Mountain"), BEACH("Beach"), PLAIN("Plain"), ROOM("My room");
  /** Passthrough shows the wearer's room; the sky is hidden whenever it is on. */
  val passthrough get() = this==ROOM
  /** A 360-degree sky surrounds the wearer here. */
  val sky get() = this==MOON || this==MOUNTAIN || this==BEACH
}

/**
 * The place switch. Pure state (no Android), so the rules are unit tested; [PlacesFeature]
 * applies it to the scene. The choice is remembered through [store].
 */
object Places {
  /** False gives the app exactly as before Places: no sky, no Settings row, no passthrough change. */
  const val ENABLED = true
  val DEFAULT = Place.MOON

  interface Store { fun read(): String?; fun write(value: String) }
  class MemoryStore(var value: String? = null) : Store {
    override fun read() = value
    override fun write(value: String) { this.value=value }
  }

  var store: Store = MemoryStore()
    set(value) { field=value; cached=null }
  private var cached: Place?=null
  private val listeners = mutableListOf<(Place) -> Unit>()

  val current: Place get() = cached ?: (parse(store.read()) ?: DEFAULT).also { cached=it }

  /** Accepts the enum name or the label, any case ("moon", "MY ROOM", "room"). */
  fun parse(name: String?): Place? {
    val n=name?.trim()?.lowercase() ?: return null
    return Place.values().firstOrNull { it.name.lowercase()==n || it.label.lowercase()==n }
      ?: if(n=="room") Place.ROOM else null
  }

  /** Unknown names are ignored and leave the current place. Returns the place now in effect. */
  fun set(name: String): Place = parse(name)?.let { set(it) } ?: current

  fun set(place: Place): Place {
    val changed=place!=current
    cached=place;store.write(place.name)
    if(changed) listeners.toList().forEach { it(place) }
    return place
  }

  /** Moon -> Mountain -> Beach -> Plain -> My room -> Moon. */
  fun cycle(): Place = set(Place.values()[(current.ordinal+1)%Place.values().size])

  fun onChange(listener: (Place) -> Unit) { listeners.add(listener) }
  fun clearListeners() { listeners.clear() }

  /** The sky shows for the three sky places with passthrough off (passthrough hides the sky). */
  fun skyVisible(place: Place, passthroughOn: Boolean) = place.sky && !passthroughOn

  fun settingsText(place: Place = current) = "Place: ${place.label}"
}
