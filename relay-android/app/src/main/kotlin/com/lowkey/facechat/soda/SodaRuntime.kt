package com.lowkey.facechat.soda

import android.content.Context
import android.util.Log
import com.google.android.libraries.assistant.soda.Soda
import com.google.android.libraries.assistant.soda.SodaJniLibraryLoader
import com.google.research.air.cosmo.lib.soda.SodaPrepareResult
import com.lowkey.facechat.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Bundled lp_cpu language pack — same checksums as ~/neural BuildConfig defaults.
 * Neural uses CPU pack on all devices until TPU packs are proven on hardware.
 */
object SodaPackStore {
  private const val ASSET_PATH = "soda/lp_cpu.zip"
  private const val TAG = "SodaPackStore"

  fun ensureExtracted(context: Context): SodaPrepareResult {
    val sha = BuildConfig.SODA_PACK_CPU_SHA256.trim()
    val size = BuildConfig.SODA_PACK_CPU_SIZE_BYTES
    if (sha.isBlank() || size <= 0L) {
      return SodaPrepareResult.Unavailable("soda_pack_unconfigured")
    }
    val cacheDir = File(context.cacheDir, "soda_packs/lp_cpu")
    val sentinel = File(cacheDir, ".extracted")
    if (sentinel.exists()) return SodaPrepareResult.Available(cacheDir)

    val staged = File(context.filesDir, "soda_packs/staged_lp_cpu.zip")
    staged.parentFile?.mkdirs()
    return try {
      context.assets.open(ASSET_PATH).use { input ->
        staged.outputStream().use { output -> input.copyTo(output) }
      }
      if (staged.length() != size || !sha256(staged).equals(sha, ignoreCase = true)) {
        staged.delete()
        return SodaPrepareResult.Unavailable("soda_pack_asset_mismatch")
      }
      cacheDir.mkdirs()
      val canonicalRoot = cacheDir.canonicalFile
      ZipInputStream(staged.inputStream()).use { zin ->
        while (true) {
          val entry = zin.nextEntry ?: break
          val out = safeZipEntryFile(canonicalRoot, entry.name)
          if (entry.isDirectory) out.mkdirs()
          else {
            out.parentFile?.mkdirs()
            out.outputStream().use { fout -> zin.copyTo(fout) }
          }
          zin.closeEntry()
        }
      }
      sentinel.writeText("ok")
      SodaPrepareResult.Available(cacheDir)
    } catch (e: IOException) {
      Log.w(TAG, "extract failed: ${e.message}")
      cacheDir.deleteRecursively()
      SodaPrepareResult.Unavailable("soda_pack_extract_failed:${e.javaClass.simpleName}")
    }
  }

  private fun safeZipEntryFile(canonicalRoot: File, entryName: String): File {
    val output = File(canonicalRoot, entryName).canonicalFile
    val rootPath = canonicalRoot.path + File.separator
    if (output != canonicalRoot && !output.path.startsWith(rootPath)) {
      throw IOException("SODA pack entry escapes target directory: $entryName")
    }
    return output
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { byte ->
      (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
  }
}

/** Native runtime probe + pack prep — slimmed from neural JniSodaCapability. */
object SodaRuntime {
  private const val TAG = "SodaRuntime"
  @Volatile private var nativeUnavailableReason: String? = null

  fun isAvailable(context: Context): Boolean {
    nativeUnavailableReason?.let { return false }
    return runCatching {
      SodaJniLibraryLoader.ensureLoaded()
      val handle = Soda.nativeCreateSharedResources(context)
      if (handle != 0L) Soda.nativeDeleteSharedResources(context, handle)
      handle != 0L
    }.fold(
        onSuccess = { it },
        onFailure = {
          nativeUnavailableReason = "soda_native_unavailable:${it.message}"
          Log.w(TAG, "SODA unavailable ($nativeUnavailableReason)")
          false
        },
      )
  }

  fun preparePack(context: Context): SodaPrepareResult = SodaPackStore.ensureExtracted(context)
}
