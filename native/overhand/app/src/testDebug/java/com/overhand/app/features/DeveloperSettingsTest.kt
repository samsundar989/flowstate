package com.overhand.app.features

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperSettingsTest {
  @Test fun debugKeepsTheCaptureServerPresets() = assertEquals(listOf("USB","Home network","Private network"),DeveloperSettings.serverPresets.map { it.first })
}
