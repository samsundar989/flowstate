package com.overhand.app.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #1079: createComposeRule() launches androidx.activity.ComponentActivity. Debug builds get it declared through
 * debugImplementation(ui-test-manifest); the release unit suite does not, and the shipped APK must not either.
 * Registering it with Robolectric's package manager (test sandbox only) lets the Compose tests run on both variants.
 * Use as the outer rule: @get:Rule(order = 0) val activity = ComponentActivityRule().
 */
class ComponentActivityRule : TestRule {
  override fun apply(base: Statement, description: Description) = object : Statement() {
    override fun evaluate() {
      val app = RuntimeEnvironment.getApplication()
      shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
      base.evaluate()
    }
  }
}
