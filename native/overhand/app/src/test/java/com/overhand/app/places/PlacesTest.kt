package com.overhand.app.places

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlacesTest {
  private lateinit var store: Places.MemoryStore

  @Before fun fresh() { Places.clearListeners();store=Places.MemoryStore();Places.store=store }
  @After fun clean() { Places.clearListeners() }

  @Test fun defaultIsMoonAndFlagIsOn() {
    assertTrue(Places.ENABLED)
    assertEquals(Place.MOON,Places.current)
    assertEquals("Place: Moon",Places.settingsText())
  }

  @Test fun cycleVisitsEveryPlaceInOrderAndWraps() {
    // t1016: Moon, Mountain, Beach, Plain, My room.
    assertEquals(Place.MOUNTAIN,Places.cycle())
    assertEquals("Place: Mountain",Places.settingsText())
    assertEquals(Place.BEACH,Places.cycle())
    assertEquals("Place: Beach",Places.settingsText())
    assertEquals(Place.PLAIN,Places.cycle())
    assertEquals(Place.ROOM,Places.cycle())
    assertEquals("Place: My room",Places.settingsText())
    assertEquals(Place.MOON,Places.cycle())
    assertEquals(5,Place.values().size)
  }

  @Test fun setAcceptsNamesAndLabelsAndIgnoresUnknown() {
    assertEquals(Place.ROOM,Places.set("my room"))
    assertEquals(Place.PLAIN,Places.set("PLAIN"))
    assertEquals(Place.ROOM,Places.set("room"))
    assertEquals(Place.ROOM,Places.set("mars"))
    assertEquals(Place.MOON,Places.set(" Moon "))
    assertEquals(Place.MOUNTAIN,Places.parse("mountain"));assertEquals(Place.MOUNTAIN,Places.parse(" MOUNTAIN "))
    assertEquals(Place.BEACH,Places.parse("Beach"));assertEquals(Place.BEACH,Places.parse("beach"))
    assertNull(Places.parse("mountains"));assertNull(Places.parse("sea"))
  }

  @Test fun choiceIsRememberedAcrossLaunches() {
    Places.set("plain")
    assertEquals("PLAIN",store.value)
    Places.store=Places.MemoryStore(store.value) // a new launch reads the same store
    assertEquals(Place.PLAIN,Places.current)
    Places.store=Places.MemoryStore("garbage")
    assertEquals(Place.MOON,Places.current)
    for(place in listOf(Place.MOUNTAIN,Place.BEACH)) {
      val launch=Places.MemoryStore();Places.store=launch
      Places.set(place)
      Places.store=Places.MemoryStore(launch.value) // restart
      assertEquals(place,Places.current)
    }
  }

  @Test fun listenersHearOnlyRealChanges() {
    val heard=mutableListOf<Place>()
    Places.onChange { heard.add(it) }
    Places.set(Place.MOON);Places.cycle();Places.set("plain");Places.set("my room")
    assertEquals(listOf(Place.MOUNTAIN,Place.PLAIN,Place.ROOM),heard)
  }

  @Test fun passthroughHidesTheSkyAndOnlyTheThreeSkyPlacesShowIt() {
    for(p in listOf(Place.MOON,Place.MOUNTAIN,Place.BEACH)) {
      assertTrue(Places.skyVisible(p,passthroughOn=false))
      assertFalse(Places.skyVisible(p,passthroughOn=true))
      assertFalse(p.passthrough)
    }
    assertFalse(Places.skyVisible(Place.PLAIN,passthroughOn=false))
    assertFalse(Places.skyVisible(Place.ROOM,passthroughOn=true))
    assertTrue(Place.ROOM.passthrough);assertFalse(Place.PLAIN.passthrough)
  }
}
