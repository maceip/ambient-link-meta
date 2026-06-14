package com.lowkey.ambientlink.hud

import android.util.Log
import com.meta.wearable.dat.display.Display

/**
 * EXPERIMENT — can we drive a FADE / SLIDE animation on the glasses?
 *
 * Findings from decompiling mwdat-display 0.7.0:
 *   - The enum `…internal.DisplayTransition { NONE, FADE, SLIDE }` exists, but
 *     it is referenced by exactly ONE message: `DisplayStopRequest`. So it is a
 *     *teardown / exit* animation (how the card animates OUT), not a present- or
 *     content-swap animation. There is no transition field on DisplayStartRequest
 *     or on the DisplayContent message.
 *   - It is NOT on the public API. `Display` only exposes getState + sendContent;
 *     the stop path (`removeDisplay(session)` / `session.stop()`) takes no
 *     transition argument. The request is built internally inside DisplaySessionImpl.
 *
 * So there is no supported way to ask for FADE/SLIDE. This probe reflects over
 * the live Display/Session objects and the internal proto builder to report what
 * (if anything) is reachable on-device, and makes a best-effort attempt to set a
 * transition on a DisplayStopRequest builder so we can see whether the wire path
 * accepts it. Run it, then read logcat (tag "ambient.transition"). Purely diagnostic —
 * do not ship this on the hot path.
 */
object DisplayTransitionProbe {
  private const val TAG = "ambient.transition"

  fun probe(display: Display) {
    Log.i(TAG, "=== DisplayTransition reachability probe ===")
    logTypeSurface(display.javaClass, "Display impl")

    // Does the internal enum + builder exist on the classpath?
    val transitionEnum = tryClass("com.meta.wearable.dat.display.internal.DisplayTransition")
    val stopReq = tryClass("com.meta.wearable.dat.display.internal.DisplayStopRequest")
    Log.i(TAG, "DisplayTransition enum present: ${transitionEnum != null}")
    Log.i(TAG, "DisplayStopRequest present:    ${stopReq != null}")

    if (transitionEnum != null) {
      val consts = transitionEnum.enumConstants?.joinToString { (it as Enum<*>).name }
      Log.i(TAG, "transition values: $consts")
    }

    // Look for any public/declared method anywhere that takes a DisplayTransition
    // or looks like a stop/remove that could carry one.
    val anySetter = stopReq?.declaredClasses
      ?.firstOrNull { it.simpleName == "Builder" }
      ?.declaredMethods
      ?.filter { it.name.contains("ransition", ignoreCase = true) }
      ?.joinToString { it.name }
    Log.i(TAG, "DisplayStopRequest.Builder transition methods: ${anySetter ?: "none reachable"}")

    Log.i(
      TAG,
      "CONCLUSION: transition is exit-only and not wired through any public " +
        "Display/Session method. A FADE/SLIDE on present/swap is not expressible. " +
        "Best we can author is content swaps via repeated sendContent().",
    )
  }

  /** Best-effort: build a FADE DisplayStopRequest reflectively (proves the proto
   *  accepts it) — but note there is no public sink to hand it to. */
  fun buildFadeStopRequestOrNull(): Any? = try {
    val enum = Class.forName("com.meta.wearable.dat.display.internal.DisplayTransition")
    val fade = enum.enumConstants?.firstOrNull { (it as Enum<*>).name.contains("FADE") }
    val stopReq = Class.forName("com.meta.wearable.dat.display.internal.DisplayStopRequest")
    val newBuilder = stopReq.getDeclaredMethod("newBuilder").apply { isAccessible = true }
    val builder = newBuilder.invoke(null)
    val setter = builder.javaClass.declaredMethods
      .firstOrNull { it.name.startsWith("setTransition") && it.parameterTypes.size == 1 }
    setter?.isAccessible = true
    setter?.invoke(builder, fade)
    val built = builder.javaClass.getMethod("build").invoke(builder)
    Log.i(TAG, "built a FADE DisplayStopRequest reflectively: $built (no public sink to send it)")
    built
  } catch (t: Throwable) {
    Log.w(TAG, "could not build transition request reflectively: ${t.message}")
    null
  }

  private fun logTypeSurface(c: Class<*>, label: String) {
    Log.i(TAG, "$label = ${c.name}")
    c.methods
      .filter { m -> m.name.let { it.contains("stop", true) || it.contains("remove", true) || it.contains("transition", true) } }
      .forEach { Log.i(TAG, "  candidate: ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})") }
  }

  private fun tryClass(name: String): Class<*>? = try { Class.forName(name) } catch (_: Throwable) { null }
}
