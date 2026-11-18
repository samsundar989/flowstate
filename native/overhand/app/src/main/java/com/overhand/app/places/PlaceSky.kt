package com.overhand.app.places

/**
 * The photographic skies (Mountain, Beach; t1016): size, memory rule, fade and the worn-check log line.
 * Pure (no Android), so the rules are unit tested; [PlacesFeature] does the decoding and the scene work.
 */
object PlaceSky {
  /** The shipped JPEGs are 8192 x 4096: about 23 pixels per degree, above a Quest 2's ~20. */
  const val FULL_W = 8192
  const val FULL_H = 4096
  /** One switch: the old sky fades out over the first half, the new one fades in over the second. */
  const val FADE_MS = 300L

  /** RGBA8 with the full mip chain the SDK builds (SceneTexture(Bitmap) calls calculateMips). */
  fun textureBytes(w: Int, h: Int) = w.toLong()*h*4*4/3
  /** Peak while a full-size sky loads: the decoded bitmap, the SDK's direct-buffer copy of it, and the texture. */
  val fullLoadPeakBytes get() = FULL_W.toLong()*FULL_H*4*2 + textureBytes(FULL_W, FULL_H)
  /** Free memory needed before a full-size decode is tried: the load peak plus 256 MiB of headroom. */
  val minFreeBytes get() = fullLoadPeakBytes + 256L*1024*1024

  /** 1 decodes 8192 x 4096; 2 decodes the 4096 x 2048 fallback from the same file. */
  fun sampleSize(availBytes: Long, lowMemory: Boolean) = if (lowMemory || availBytes < minFreeBytes) 2 else 1

  fun pixelsPerDegree(width: Int) = width/360.0

  /** Sky brightness while the outgoing sky fades: 1 -> 0 over the first half of [FADE_MS]. */
  fun fadeOut(elapsedMs: Long) = (1f - elapsedMs.toFloat()/(FADE_MS/2)).coerceIn(0f, 1f)
  /** Sky brightness while the incoming sky fades: 0 -> 1 over the second half of [FADE_MS]. */
  fun fadeIn(elapsedMs: Long) = (elapsedMs.toFloat()/(FADE_MS/2)).coerceIn(0f, 1f)

  /** The worn check reads this line: decode_ms under 1500 and fallback=0 on a Quest 2. */
  fun enterLine(place: Place, w: Int, h: Int, decodeMs: Long, fallback: Boolean, uploadMs: Long = 0) =
    "place event=enter place=${place.name.lowercase()} tex=${w}x$h decode_ms=$decodeMs fallback=${if (fallback) 1 else 0} upload_ms=$uploadMs"
}
