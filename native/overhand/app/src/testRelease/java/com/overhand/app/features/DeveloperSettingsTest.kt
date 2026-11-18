package com.overhand.app.features

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperSettingsTest {
  @Test fun releaseHasNoServerPresets() = assertEquals(emptyList<Pair<String,String>>(),DeveloperSettings.serverPresets)
}
