import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  // t772: the Flowstate design system is Compose over the Horizon UI Set (design/).
  alias(libs.plugins.jetbrains.kotlin.plugin.compose)
}

val localSigning = Properties().apply {
  rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun releaseSetting(name: String): String? = System.getenv(name) ?: localSigning.getProperty(name)

// scripts/build_release.ps1 passes both; ordinary builds pass neither and keep the defaults below.
val requestedVersionCode: Int? = providers.gradleProperty("overhandVersionCode").orNull?.let { raw ->
  require(Regex("[1-9][0-9]{0,9}").matches(raw) && raw.toLong() <= 2_100_000_000L) {
    "overhandVersionCode must be an integer from 1 to 2100000000"
  }
  raw.toInt()
}
val requestedVersionName: String? = providers.gradleProperty("overhandVersionName").orNull?.also { raw ->
  require(Regex("[0-9]{1,6}(\\.[0-9]{1,6}){1,3}").matches(raw)) {
    "overhandVersionName must be dotted digits, such as 0.3"
  }
}
// #1111: a debug/rc build name such as 0.4-nov15d-pre (scripts/freeze_gates.ps1); the release keeps dotted digits above.
val requestedBuildName: String? = providers.gradleProperty("overhandBuildName").orNull?.also { raw ->
  require(Regex("[0-9]+(\\.[0-9]+)+-[a-z0-9-]+").matches(raw)) { "overhandBuildName looks like 0.4-nov15d-pre" }
}
// #1201 nov15e launch cuts: -PoverhandCuts=voice,sky,... (scripts/freeze_gates.ps1 -Cuts). Read here only; the app reads
// BuildConfig.CUTS (features/LaunchCuts.kt). "mesh" is build-only: freeze_gates.ps1 maps it to -PoverhandSiteOnce=true.
val launchCutNames = listOf("voice", "sky", "windows", "rate90", "boost", "site_hidden", "mesh")
val launchCuts: String = (providers.gradleProperty("overhandCuts").orNull ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
  .also { c -> require(launchCutNames.containsAll(c)) { "overhandCuts: unknown ${c - launchCutNames.toSet()}; known $launchCutNames" } }
  .distinct().sortedBy { launchCutNames.indexOf(it) }.joinToString(",")
require((requestedVersionCode == null) == (requestedVersionName == null)) {
  "Pass overhandVersionCode and overhandVersionName together"
}

// Voice (t756, t805): the recogniser library and its model ship inside every build, from data/voice/bundle
// (the AAR plus assets/moonshine; tools/voice_judges/fetch_voice_model.sh fills it, never in git).
// -PoverhandVoice=<dir> uses another folder, -PoverhandVoice=off builds without voice.
val voiceProp: String? = providers.gradleProperty("overhandVoice").orNull
val voiceDir: File? = when (voiceProp) {
  "off" -> null
  null -> rootProject.file("../../data/voice/bundle").takeIf {
    File(it, "sherpa-onnx-static-link-onnxruntime-2.0.1.aar").isFile && File(it, "assets/moonshine/encoder_model.ort").isFile
  } ?: null.also { println("Voice off: data/voice/bundle has no recogniser; run tools/voice_judges/fetch_voice_model.sh") }
  else -> file(voiceProp)
}
val voiceAar: File? = voiceDir?.let { File(it, "sherpa-onnx-static-link-onnxruntime-2.0.1.aar") }?.also {
  require(it.isFile) { "overhandVoice: missing $it" }
}

android {
  namespace = "com.overhand.app"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.overhand.app"
    // Horizon OS is Android 14 (API 34)
    minSdk = 34
    targetSdk = 34
    versionCode = requestedVersionCode ?: 6
    versionName = requestedBuildName ?: requestedVersionName ?: "0.4-nov15c"
  }

  packaging { resources.excludes.add("META-INF/LICENSE") }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  signingConfigs {
    create("release") {
      storeFile = rootProject.file(releaseSetting("RELEASE_KEYSTORE") ?: "keystore/overhand-release.jks")
      storePassword = releaseSetting("RELEASE_STORE_PASSWORD")
      keyAlias = releaseSetting("RELEASE_KEY_ALIAS") ?: "overhand-release"
      keyPassword = releaseSetting("RELEASE_KEY_PASSWORD")
      enableV2Signing = true
    }
  }

  buildTypes {
    release {
      isDebuggable = false
      isMinifyEnabled = false
      signingConfig = signingConfigs.getByName("release")
    }
    // #1088: the release configuration under com.overhand.app.rc, so it installs BESIDE the debug app (no uninstall,
    // saved places kept). Debug key only, never the release keystore: a tailnet launch candidate, never uploaded.
    // Same code as release: src/release and src/testRelease are added below; nothing else may differ.
    create("rc") {
      initWith(getByName("release"))
      applicationIdSuffix = ".rc"
      versionNameSuffix = "-rc"
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
    }
  }
  sourceSets.getByName("rc") { java.srcDir("src/release/java") }
  sourceSets.getByName("testRc") { java.srcDir("src/testRelease/java") }
  buildFeatures { buildConfig = true; compose = true }
  // t772: Robolectric screenshot tests (Roborazzi) need merged resources (UI Set fonts and icons).
  testOptions { unitTests.isIncludeAndroidResources = true }
  defaultConfig { buildConfigField("boolean", "VOICE", (voiceDir != null).toString()) }
  defaultConfig { buildConfigField("String", "CUTS", "\"$launchCuts\"") }
  defaultConfig { buildConfigField("boolean", "SITE_ONCE", (providers.gradleProperty("overhandSiteOnce").orNull == "true").toString()) } // #1202: site pieces loaded once, off by default
  // #1032: the one host Smart answers may reach (relay/CONTRACT.md). -PrelayUrl= (empty) builds without it.
  defaultConfig { buildConfigField("String", "RELAY_URL", "\"" + ((project.findProperty("relayUrl") as String?) ?: "") + "\"") }
  // Quest is arm64 only (the sherpa-onnx AAR alone carries three other ABIs, ~76 MB). Native libraries are
  // compressed in the APK and unpacked at install: the download is ~45 MB smaller (t805 size table).
  defaultConfig { ndk { abiFilters += "arm64-v8a" } }
  packaging { jniLibs.useLegacyPackaging = true }
  if (voiceDir != null) {
    sourceSets.getByName("main") { java.srcDir("src/voice/java"); assets.srcDir(File(voiceDir, "assets")) }
    // The mic permission is an overlay merged into the app manifest (a main srcFile would replace it).
    for (t in listOf("debug", "release", "rc")) sourceSets.getByName(t) { manifest.srcFile("src/voice/AndroidManifest.xml") }
    sourceSets.getByName("testDebug") { java.srcDir("src/voiceTest/java") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  testOptions { unitTests.isIncludeAndroidResources = true }
}

// #1088: Kotlin mangles `internal` names with the module name (<project>_<variant>). Under its own name the rc dex
// differs from release in 28 classes (getLambda-1$app_rc, and R8 renumbers lambdas around it). Compiling the rc under
// release's module name makes its dex the release dex (job 5835 static_check.txt).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().matching { it.name == "compileRcKotlin" }.configureEach {
  compilerOptions.moduleName.set("app_release")
}
// #1111: unit-test forks ran at Gradle's 512 MB default; PlaceSkyTest's 8192x4096 sky decode OOMed in the debug fork (job 5881).
tasks.withType<Test>().matching { it.name.endsWith("UnitTest") }.configureEach { maxHeapSize = "2g" }

dependencies {
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")
  testImplementation("org.robolectric:robolectric:4.13")
  implementation(libs.androidx.core.ktx)
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.isdk)
  implementation(libs.meta.spatial.sdk.mruk)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  implementation("com.squareup.okhttp3:okhttp:4.12.0") // t786 hostlink: pinned-TLS WebSocket to the host (Apache-2.0)
  // t772: Flowstate design system (Compose over the Horizon UI Set) and its off-device screenshot test.
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.material3)
  // t907: ComposePreview (main source set) uses savedstate/viewmodel owners; debug got them only via ui-test-manifest, so release failed to compile.
  implementation(libs.androidx.activity.compose)
  implementation(libs.meta.spatial.sdk.uiset)
  // t872: design/ComposePreview.kt needs these on the release classpath; only debug got them (via ui-test-manifest).
  implementation("androidx.savedstate:savedstate-ktx:1.2.1")
  implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.3")
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation(libs.androidx.ui.test.junit4)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  debugImplementation(libs.androidx.ui.test.manifest)
  if (voiceAar != null) implementation(files(voiceAar))
}

// Layout lint gate (#773). The release build renders every window off-device (Robolectric native
// graphics, LayoutLintCapture only) and fails when violations exceed tools/layout_lint/baseline.json.
// tools/layout_lint/run.cmd is the same pipeline by hand. -PskipLayoutLint skips it (say why in the report).
val layoutLintRoot = rootProject.file("../..")
val layoutLintOut = providers.gradleProperty("layoutLintOut").orNull?.let { file(it) }
  ?: File(layoutLintRoot, "runs/layout_lint/gate")
afterEvaluate {
  // #1088: -PlayoutLintVariant=Rc renders the release-configuration windows (shorter Settings) instead of debug's.
  val lintVariant = providers.gradleProperty("layoutLintVariant").orNull ?: "Debug"
  val unit = tasks.named<Test>("test${lintVariant}UnitTest").get()
  val capture = tasks.register<Test>("layoutLintCapture") {
    description = "Render every window to PNG + element JSON (layout lint)"
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    dependsOn(unit.dependsOn)
    filter.includeTestsMatching("com.overhand.app.layoutlint.LayoutLintCapture")
    systemProperty("layoutLint.out", layoutLintOut.absolutePath)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    outputs.upToDateWhen { false }
    maxHeapSize = "3g"
  }
  val gate = tasks.register<Exec>("layoutLintGate") {
    description = "Lint the captured windows and compare with the checked-in baseline"
    dependsOn(capture)
    workingDir = layoutLintRoot
    commandLine("python", "tools/layout_lint/lint.py", layoutLintOut.absolutePath, "--check-baseline")
  }
  if (!providers.gradleProperty("skipLayoutLint").isPresent) {
    tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(gate) }
  }
  // APK privacy gate (#808): scan the packaged release APK for private names (data/apk_privacy, gitignored),
  // home paths, user and PC name; a planted control must be caught too. No -Pskip: a hit is fixed in the bundle builder.
  val privacy = tasks.register<Exec>("apkPrivacyGate") {
    description = "Scan the built release APK for private text (tools/apk_privacy_gate)"
    dependsOn("packageRelease")
    workingDir = layoutLintRoot
    commandLine("python", "tools/apk_privacy_gate/gate.py", file("build/outputs/apk/release/app-release.apk").absolutePath,
      "--self-test", "--out", "runs/apk_privacy_gate/release.json")
  }
  tasks.named("assembleRelease") { dependsOn(privacy) }
  // Brand gate (#879, rules s.4): every string the capture rendered, res/ + assets/ copy and the packaged APK's text
  // resources must name no vendor, product or model (tools/brand_gate/words.txt); a planted control must be caught.
  // No -Pskip: a hit is fixed in the copy, and only text never shown may enter tools/brand_gate/allowlist.json.
  val brand = tasks.register<Exec>("brandGate") {
    description = "Scan rendered windows, copy and the release APK for vendor/product/model names (tools/brand_gate)"
    dependsOn(capture, "packageRelease")
    workingDir = layoutLintRoot
    commandLine("python", "tools/brand_gate/gate.py", "--capture", layoutLintOut.absolutePath,
      "--src", file("src/main").absolutePath, "--apk", file("build/outputs/apk/release/app-release.apk").absolutePath,
      "--self-test", "--out", "runs/brand_gate/release.json")
  }
  tasks.named("assembleRelease") { dependsOn(brand) }
  // t922: inspect the packaged bytes, even when configuration silently disabled voice.
  // No skip switch: a release without the offline recogniser must never ship.
  val voice = tasks.register<Exec>("voiceGate") {
    description = "Require the offline voice models and arm64 recogniser in the release APK"
    dependsOn("packageRelease")
    workingDir = layoutLintRoot
    commandLine("python", "tools/voice_gate/gate.py",
      file("build/outputs/apk/release/app-release.apk").absolutePath,
      "--out", "runs/voice_gate/release.json")
  }
  tasks.named("assembleRelease") { dependsOn(voice) }
}
