package com.overhand.app.places

import com.overhand.app.features.SettingsPanel
import com.overhand.app.features.WindowLayout
import com.overhand.app.windows.Theme
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/**
 * t1016: the photographic places, checked from the shipped files in res/drawable-nodpi.
 * Contrast: window text (Theme.TEXT) over the place's window backing (Theme.panelFill, its alpha) composited over the
 * brightest sky pixel behind each default window (WindowLayout.slots and defaultSpecs sizes), under the worse of
 * two blend models (linear light and sRGB values). tools/places/photo_contrast.py computes the same numbers.
 */
class PlaceSkyTest {
  private val res=File("src/main/res/drawable-nodpi")

  /** The JDK's ImageIO, reached by reflection: the unit-test compile classpath is android.jar, which lacks javax.imageio. */
  private class Img(f: File) {
    private val im: Any=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,f)!!
    private val rgb=im.javaClass.getMethod("getRGB",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
    val width=im.javaClass.getMethod("getWidth").invoke(im) as Int
    val height=im.javaClass.getMethod("getHeight").invoke(im) as Int
    fun getRGB(x: Int,y: Int)=rgb.invoke(im,x,y) as Int
  }

  /** #1111: width and height from the JPEG header alone; a full 8192x4096 decode ran the shared 3 GB test fork out of heap. */
  private fun headerSize(f: File): Pair<Int,Int> {
    val io=Class.forName("javax.imageio.ImageIO")
    val stream=io.getMethod("createImageInputStream",Any::class.java).invoke(null,f)!!
    val reader=(io.getMethod("getImageReaders",Any::class.java).invoke(null,stream) as Iterator<*>).next()!!
    val rc=Class.forName("javax.imageio.ImageReader")
    rc.getMethod("setInput",Any::class.java).invoke(reader,stream)
    try { return (rc.getMethod("getWidth",Int::class.javaPrimitiveType).invoke(reader,0) as Int) to
      (rc.getMethod("getHeight",Int::class.javaPrimitiveType).invoke(reader,0) as Int) }
    finally { rc.getMethod("dispose").invoke(reader);(stream as java.io.Closeable).close() }
  }

  private fun lin(c: Double)=if(c<=0.04045) c/12.92 else ((c+0.055)/1.055).pow(2.4)
  private fun lum(r: Int,g: Int,b: Int)=0.2126*lin(r/255.0)+0.7152*lin(g/255.0)+0.0722*lin(b/255.0)
  private fun rgba(hex: String)=hex.removePrefix("#").let { v -> (0 until 4).map { v.substring(it*2,it*2+2).toInt(16) } }
  private fun enc(l: Double)=255*(if(l<=0.0031308) l*12.92 else 1.055*l.pow(1/2.4)-0.055)

  /** Worst of the two blend models, sky taken as a grey of luminance [sky]. */
  private fun contrast(sky: Double, fill: String): Double {
    val t=rgba(Theme.TEXT);val f=rgba(fill);val a=f[3]/255.0
    val lt=lum(t[0],t[1],t[2]);val lf=lum(f[0],f[1],f[2])
    val cLin=(lt+0.05)/(a*lf+(1-a)*sky+0.05)
    val g=enc(sky)
    val s=0.2126*lin((a*f[0]+(1-a)*g)/255)+0.7152*lin((a*f[1]+(1-a)*g)/255)+0.0722*lin((a*f[2]+(1-a)*g)/255)
    return min(cLin,(lt+0.05)/(s+0.05))
  }

  /** Brightest sky luminance behind a window centred at (bearing, elevation, distance), w x h metres, facing the eye. */
  private fun backdropMax(img: Img,bearing: Double,elev: Double,dist: Double,w: Double,h: Double): Double {
    val b=Math.toRadians(bearing);val e=Math.toRadians(elev)
    val c=doubleArrayOf(dist*cos(e)*sin(b),dist*sin(e),dist*cos(e)*cos(b))
    val yaw=atan2(c[0],c[2]);val rx=cos(yaw);val rz=-sin(yaw)
    var mx=0.0
    for(i in 0 until 48) for(j in 0 until 30) {
      val u=((i+0.5)/48-0.5)*w;val v=((j+0.5)/30-0.5)*h
      val x=c[0]+u*rx;val y=c[1]+v;val z=c[2]+u*rz;val n=sqrt(x*x+y*y+z*z)
      val az=atan2(x/n,z/n);val el=asin((y/n).coerceIn(-1.0,1.0))
      val col=Math.floorMod(floor((az+PI)/(2*PI)*img.width).toInt(),img.width)
      val row=floor((PI/2-el)/PI*img.height).toInt().coerceIn(0,img.height-1)
      val p=img.getRGB(col,row);mx=max(mx,lum(p shr 16 and 255,p shr 8 and 255,p and 255))
    }
    return mx
  }

  /** The five dashboard windows at their slots; Settings where it opens (SettingsPanel spawn pose, scaled quad). */
  private fun windows()=WindowLayout.slots.map { (view,sl) ->
    val spec=WindowLayout.defaultSpecs.first { it.view==view }
    if(view==SettingsPanel.VIEW) Triple(view,doubleArrayOf(SettingsPanel.BEARING_DEG,SettingsPanel.ELEVATION_DEG,SettingsPanel.DISTANCE_M),
      doubleArrayOf(spec.w*SettingsPanel.SCALE,spec.h*SettingsPanel.SCALE))
    else Triple(view,doubleArrayOf(sl.bearingDeg,sl.elevationDeg,sl.distanceM),doubleArrayOf(spec.w.toDouble(),spec.h.toDouble()))
  }

  @Test fun everyDefaultWindowKeepsBodyTextAt45To1InBothPhotoPlaces() {
    assertEquals(6,windows().size)
    for(name in listOf("place_mountain","place_beach")) {
      val fill=Theme.panelFill(if(name=="place_mountain") Place.MOUNTAIN else Place.BEACH)
      val img=Img(File(res,"$name.jpg"))
      var brightest=0.0
      val worst=windows().minOf { (view,p,s) ->
        val sky=backdropMax(img,p[0],p[1],p[2],s[0],s[1]);brightest=max(brightest,sky)
        val c=contrast(sky,fill)
        println("$name $view contrast=${"%.2f".format(c)}")
        c
      }
      println("$name min_contrast=$worst fill=$fill backdrop_max=$brightest")
      assertTrue("$name: $worst",worst>=4.5)
      val previous=fill.take(7)+(rgba(fill)[3]-1).toString(16).padStart(2,'0')
      assertTrue("$name alpha must be minimal",contrast(brightest,previous)<4.5)
    }
  }

  @Test fun shippedSkiesAre8192By4096AndUnder15MB() {
    for(name in listOf("place_mountain","place_beach")) {
      val f=File(res,"$name.jpg")
      val r=headerSize(f)
      assertEquals(PlaceSky.FULL_W to PlaceSky.FULL_H,r)
      assertTrue(f.length()<15_000_000)
      val sha=MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
      println("$name ${r.first}x${r.second} ppd=${"%.2f".format(PlaceSky.pixelsPerDegree(r.first))} bytes=${f.length()} sha256=$sha")
    }
    assertTrue(PlaceSky.pixelsPerDegree(PlaceSky.FULL_W)>=18.0)
  }

  @Test fun lowMemoryOrTooLittleFreeMemoryDecodesTheHalfSizeCopy() {
    assertEquals(1,PlaceSky.sampleSize(4L shl 30,lowMemory=false))
    assertEquals(2,PlaceSky.sampleSize(4L shl 30,lowMemory=true))
    assertEquals(2,PlaceSky.sampleSize(PlaceSky.minFreeBytes-1,lowMemory=false))
    // 8192 x 4096 RGBA with mips is 179 MB; the half-size fallback is a quarter of that.
    assertEquals(178_956_970L,PlaceSky.textureBytes(8192,4096))
    assertTrue(kotlin.math.abs(PlaceSky.textureBytes(8192,4096)/4-PlaceSky.textureBytes(4096,2048))<=1L)
  }

  @Test fun aSwitchFadesOutThenInWithin300Ms() {
    assertEquals(1f,PlaceSky.fadeOut(0),0f);assertEquals(0f,PlaceSky.fadeOut(150),0f)
    assertEquals(0f,PlaceSky.fadeIn(0),0f);assertEquals(1f,PlaceSky.fadeIn(150),0f)
    assertEquals(0.5f,PlaceSky.fadeIn(75),1e-6f)
    assertEquals(300L,PlaceSky.FADE_MS)
  }

  @Test fun enterLineHasTheWornCheckFields() {
    assertEquals("place event=enter place=mountain tex=8192x4096 decode_ms=812 fallback=0 upload_ms=40",
      PlaceSky.enterLine(Place.MOUNTAIN,8192,4096,812,false,40))
    assertTrue(PlaceSky.enterLine(Place.BEACH,4096,2048,900,true).contains("place=beach tex=4096x2048 decode_ms=900 fallback=1"))
  }
}
