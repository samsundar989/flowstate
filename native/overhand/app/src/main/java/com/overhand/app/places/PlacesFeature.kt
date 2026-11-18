@file:OptIn(com.meta.spatial.core.SpatialSDKExperimentalAPI::class)
package com.overhand.app.places

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.overhand.app.R
import com.overhand.app.features.Feature
import com.overhand.app.features.SettingsRow
import com.overhand.app.features.Shell
import com.overhand.app.features.settingsButton

/**
 * Places: Moon (one baked equirect on the SDK skybox, unlit), Plain (the virtual space as before),
 * My room (passthrough). Passthrough goes through [com.overhand.app.mr.MixedRealityMode.toggle],
 * the same path as the Settings checkbox. Inert when [Places.ENABLED] is false.
 *
 * SDK (meta-spatial-sdk-toolkit 0.15.0, read from the Gradle cache with javap):
 * `Mesh(Uri.parse("mesh://skybox"), MeshCollision.NoCollision)` is built by
 * ToolkitFeature.createSkyBoxMesh as `SceneMesh.skybox(950f, material)` from the entity's
 * `Material`; `Material.baseTextureAndroidResourceId` and `Material.unlit` set the texture and
 * skip lighting, so the sky costs one textured sphere and no light evaluation.
 *
 * Mountain and Beach (t1016) are 8192 x 4096 photographs and do NOT use that path: the SDK decodes a
 * `baseTextureAndroidResourceId` with BitmapFactory.decodeResource on the thread that builds the material
 * (SceneTexture.kt:312-336 via Material.generateSceneMaterial, Material.kt:148-161) and keeps every texture in a
 * static cache for the life of the process (Material.sceneTextureCache), so two 8K skies would stay resident and
 * each first entry would stall a frame. Instead: decode on a background thread ([decode]), build the SceneTexture
 * (the SDK copies to a direct buffer and makes the full mip chain, SceneTexture.kt:172-183) on the ECS thread, show
 * it on our own skybox mesh ([PHOTO_MESH], SceneMesh.skybox as the toolkit does), fade with the material's albedo,
 * and destroy the old sky's texture after the swap so one photo sky is resident at a time. Low memory or a failed
 * decode falls back to a 4096 x 2048 decode of the same file. Moon keeps its original path unchanged.
 */
class PlacesFeature(private val s: Shell) : Feature {
  override val name = "places"
  private var sky: Entity?=null // ECS thread only
  private var skyShown=false

  // Photo skies (Mountain, Beach). ECS thread unless noted.
  private class Photo(val place: Place, val entity: Entity, val material: SceneMaterial, val texture: SceneTexture)
  private class Decoded(val place: Place, val bitmap: Bitmap, val decodeMs: Long, val fallback: Boolean)
  private var photo: Photo?=null
  private var photoAt=0L          // when [photo] started fading in
  private var outgoing: Photo?=null
  private var outgoingAt=0L
  private var loading: Place?=null
  private val ready=java.util.concurrent.atomic.AtomicReference<Decoded?>(null) // decode thread -> ECS
  private var meshMaterial: SceneMaterial?=null // read by the [PHOTO_MESH] creator
  private var lastEntered: Place?=null

  // onPreCreate: before Settings rows are built and before MixedRealityFeature reads passthrough.
  override fun onPreCreate() {
    if(!Places.ENABLED) return
    Places.store=object : Places.Store {
      override fun read()=s.prefs.getString(PREF,null)
      override fun write(value: String) { s.prefs.edit().putString(PREF,value).apply() }
    }
    // The remembered place decides passthrough.
    s.prefs.edit().putBoolean("passthrough",Places.current.passthrough).apply()
    Places.onChange { place -> s.ui.post { apply(place) } }
    Log.i(TAG,"place=${Places.current}")
  }

  /** UI thread. Passthrough follows the place; the frame loop shows or hides the sky. */
  private fun apply(place: Place) {
    if(s.stopped) return
    com.overhand.app.windows.Theme.refreshPanels()
    s.dashboards.refreshTheme()
    Log.i(TAG,"place=$place")
    val mode=s.mr.roomMode
    if(mode==null) s.prefs.edit().putBoolean("passthrough",place.passthrough).apply()
    else if(mode.enabled!=place.passthrough) mode.toggle(place.passthrough,requestAccess=false)
  }

  override fun onSceneReady() {
    if(!Places.ENABLED) return
    s.activity.registerMeshCreator(PHOTO_MESH) { _: Entity -> SceneMesh.skybox(950f,meshMaterial!!) }
    // Every frame (onFrame is ~10 Hz): the 0.3 s fade.
    s.activity.systemManager.registerSystem(object : SystemBase() {
      override fun execute() { if(!s.stopped) fade(SystemClock.uptimeMillis()) }
    })
  }

  override fun onFrame(now: Long) {
    if(!Places.ENABLED) return
    val place=Places.current
    val visible=Places.skyVisible(place,s.prefs.getBoolean("passthrough",true))
    photos(if(visible) place.takeIf { it==Place.MOUNTAIN || it==Place.BEACH } else null)
    // Moon stays up while a photo sky is still decoding, so a switch never shows an empty frame.
    val want=visible && place==Place.MOON || (skyShown && loading!=null)
    if(place!=lastEntered && (!place.sky || place==Place.MOON)) {
      lastEntered=place
      Log.i(TAG,if(place==Place.MOON) PlaceSky.enterLine(place,4096,2048,-1,false) else PlaceSky.enterLine(place,0,0,0,false))
    }
    if(want && sky==null) {
      // Forward of the sky (azimuth 0 of the bake) follows the layout's forward, plus SKY_YAW_DEG.
      val q=s.layout.origin.q*Quaternion(0f,SKY_YAW_DEG,0f)
      sky=Entity.create(listOf( // create-once: only while sky is null
        Mesh(Uri.parse("mesh://skybox"),MeshCollision.NoCollision),
        Material().apply { baseTextureAndroidResourceId=R.drawable.place_moon;unlit=true },
        Transform(Pose(Vector3(0f),q)),Visible(true)))
      skyShown=true
    } else if(sky!=null && want!=skyShown) {
      sky?.setComponent(Visible(want));skyShown=want
    }
  }

  /** ECS thread. [want] is the photo place to show, or null for none. */
  private fun photos(want: Place?) {
    ready.getAndSet(null)?.let { d ->
      if(d.place!=want) { loading=null;d.bitmap.recycle() } else show(d)
    }
    photo?.let { p -> if(p.place!=want && outgoing==null) { outgoing=p;outgoingAt=SystemClock.uptimeMillis();photo=null } }
    if(want!=null && photo==null && loading==null) { loading=want;decode(want,1) }
  }

  /** Background thread: decode the JPEG; full size unless memory is low; half size if the full decode fails. */
  private fun decode(place: Place,sample: Int) {
    val res=if(place==Place.MOUNTAIN) R.drawable.place_mountain else R.drawable.place_beach
    val ctx=s.activity
    Thread({
      val mi=ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
      var n=maxOf(sample,PlaceSky.sampleSize(mi.availMem,mi.lowMemory))
      val t0=SystemClock.uptimeMillis()
      fun load(n: Int): Bitmap? = try {
        BitmapFactory.decodeResource(ctx.resources,res,BitmapFactory.Options().apply {
          inSampleSize=n;inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888 })
      } catch(e: Throwable) { Log.w(TAG,"place decode failed place=$place sample=$n: $e");null }
      var bmp=load(n)
      if(bmp==null && n==1) { n=2;bmp=load(n) }
      if(bmp==null) {
        Log.e(TAG,"place event=enter place=${place.name.lowercase()} tex=0x0 decode_ms=-1 fallback=1 error=decode")
        return@Thread
      }
      if(n>1) Log.w(TAG,"place fallback place=$place sample=$n avail_mb=${mi.availMem shr 20} low=${mi.lowMemory}")
      ready.set(Decoded(place,bmp,SystemClock.uptimeMillis()-t0,n>1))
    },"place-decode").apply { isDaemon=true;priority=Thread.MIN_PRIORITY;start() }
  }

  /** ECS thread: upload the decoded sky and start its fade-in (after the old photo sky has faded out). */
  private fun show(d: Decoded) {
    if(outgoing!=null) { ready.compareAndSet(null,d);return }
    loading=null
    val t0=SystemClock.uptimeMillis()
    val texture=try { SceneTexture(d.bitmap) } catch(e: Throwable) {
      Log.w(TAG,"place upload failed place=${d.place} w=${d.bitmap.width}: $e");d.bitmap.recycle()
      if(!d.fallback) { loading=d.place;decode(d.place,2) }
      return
    }
    val w=d.bitmap.width;val h=d.bitmap.height
    d.bitmap.recycle()
    val material=SceneMaterial(texture,AlphaMode.OPAQUE,"").apply { setUnlit(true);setAlbedoColor(Color.valueOf(0f,0f,0f,1f)) }
    meshMaterial=material
    val q=s.layout.origin.q*Quaternion(0f,SKY_YAW_DEG,0f)
    val e=Entity.create(listOf(Mesh(Uri.parse(PHOTO_MESH),MeshCollision.NoCollision),Transform(Pose(Vector3(0f),q)),Visible(true))) // create-once: one per entered photo place, destroyed when it is left
    photo=Photo(d.place,e,material,texture);photoAt=SystemClock.uptimeMillis()
    if(skyShown) { sky?.setComponent(Visible(false));skyShown=false } // the Moon gives way as the photo fades in
    lastEntered=d.place
    Log.i(TAG,PlaceSky.enterLine(d.place,w,h,d.decodeMs,d.fallback,SystemClock.uptimeMillis()-t0))
  }

  /** ECS thread, every frame: the outgoing photo sky fades out, then the new one fades in. */
  private fun fade(now: Long) {
    outgoing?.let { o ->
      val k=PlaceSky.fadeOut(now-outgoingAt)
      o.material.setAlbedoColor(Color.valueOf(k,k,k,1f))
      if(k<=0f) {
        outgoing=null;o.entity.destroy()
        // Released once the renderer has let go of the old mesh.
        s.ui.postDelayed({ s.commands.add { o.texture.destroy();o.material.destroy() } },1000)
      }
    }
    photo?.let { p -> if(now-photoAt<=PlaceSky.FADE_MS) { val k=PlaceSky.fadeIn(now-photoAt);p.material.setAlbedoColor(Color.valueOf(k,k,k,1f)) } }
  }

  override fun onDestroy() { Places.clearListeners() }

  override fun onDebugIntent(intent: Intent) {
    if(!Places.ENABLED) return
    intent.getStringExtra("place")?.let { Places.set(it) }
    if(intent.getBooleanExtra("place_cycle",false)) Places.cycle()
  }

  // Order 60 puts the button in Settings' frequent (hand-reachable) grid beside passthrough.
  override fun settingsRows() = if(!Places.ENABLED) emptyList() else listOf(SettingsRow(60,com.overhand.app.features.SettingsTab.ROOM) { root, _ ->
    settingsButton(root,Places.settingsText()) { Places.cycle() }
    val button=root.getChildAt(root.childCount-1) as Button
    Places.onChange { place -> s.ui.post { button.text=Places.settingsText(place) } }
  })

  companion object {
    const val TAG="OverhandPlaces"
    const val PREF="place"
    /** Turns the baked sky about the vertical if the device shows the Earth off its intended bearing. */
    const val SKY_YAW_DEG=0f
    /** Our skybox mesh for the photo skies; its material comes from [meshMaterial]. */
    const val PHOTO_MESH="mesh://place_photo"
  }
}
