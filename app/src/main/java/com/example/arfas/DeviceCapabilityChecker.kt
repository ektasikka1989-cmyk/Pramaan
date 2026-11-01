package com.example.arfas

import android.app.ActivityManager
import android.content.Context
import android.util.Log

private const val TAG = "DeviceCapabilityChecker"

/**
 * Lightweight preflight only. The real local-capability decision is the actual
 * LiteRT-LM engine load + warm-up probe in LocalModelManager.
 *
 * We deliberately do not hard-code a device/model allow-list. Android's low-RAM
 * signal and current memory pressure decide whether attempting a local load is
 * reasonable; GPU failure is handled by the runtime's GPU -> CPU fallback.
 */
object DeviceCapabilityChecker {

    enum class ModelTier { NONE, STANDARD }

    data class DeviceProfile(
        val totalRamMB: Long,
        val availableRamMB: Long,
        val modelTier: ModelTier,
        val canRunLocalModel: Boolean,
        val hasGpuDelegate: Boolean,
        val lowRamDevice: Boolean
    )

    fun getProfile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        val availRamMB = memInfo.availMem / (1024 * 1024)
        val lowRam = am.isLowRamDevice
        val hasGpu = hasOpenCL()

        // This is only a conservative preflight to avoid obviously unsafe attempts.
        // LocalModelManager's real native initialize + warm-up remains authoritative.
        val memoryPressureSafe = !memInfo.lowMemory && availRamMB >= 768
        val reasonableDevice = !lowRam && totalRamMB >= 2_500
        val canAttempt = memoryPressureSafe && reasonableDevice
        val tier = if (canAttempt) ModelTier.STANDARD else ModelTier.NONE

        Log.d(
            TAG,
            "ram=${totalRamMB}MB available=${availRamMB}MB lowRam=$lowRam " +
                "openCl=$hasGpu canAttemptLocal=$canAttempt"
        )

        return DeviceProfile(
            totalRamMB = totalRamMB,
            availableRamMB = availRamMB,
            modelTier = tier,
            canRunLocalModel = canAttempt,
            hasGpuDelegate = hasGpu,
            lowRamDevice = lowRam
        )
    }

    private fun hasOpenCL(): Boolean = try {
        System.loadLibrary("OpenCL")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun getCapabilityDescription(profile: DeviceProfile): String = when {
        !profile.canRunLocalModel ->
            "Local AI is disabled by the device memory preflight; cloud remains available."
        !profile.hasGpuDelegate ->
            "Local AI will attempt the CPU backend; the final capability check is the model warm-up."
        else ->
            "Local AI will attempt GPU first; the final capability check is the model warm-up."
    }

    fun getCapabilityDescription(tier: ModelTier): String = when (tier) {
        ModelTier.STANDARD -> "Local AI can be attempted; runtime warm-up confirms readiness."
        ModelTier.NONE -> "Cloud AI remains available; local inference was skipped by the memory preflight."
    }
}
