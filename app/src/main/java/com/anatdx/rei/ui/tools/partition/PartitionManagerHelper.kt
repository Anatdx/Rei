package com.anatdx.rei.ui.tools.partition

import android.content.Context
import android.util.Log
import com.anatdx.rei.core.reid.ReidClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Partition helper: flash subcommand (reid/ksud/apd) for partition info.
 */
object PartitionManagerHelper {
    private const val TAG = "PartitionManagerHelper"

    /** Get slot info. */
    suspend fun getSlotInfo(context: Context): SlotInfo? = withContext(Dispatchers.IO) {
        try {
            val r = ReidClient.exec(context, listOf("flash", "slots"), timeoutMs = 15_000L)
            val lines = r.output.lines()
            Log.d(TAG, "Slots result: success=${r.exitCode == 0}, lines=${lines.size}")

            if (lines.isEmpty()) {
                return@withContext SlotInfo(
                    isAbDevice = false,
                    currentSlot = null,
                    otherSlot = null
                )
            }

            var isAbDevice = true
            var currentSlot: String? = null
            var otherSlot: String? = null

            lines.forEach { line ->
                when {
                    line.contains("This device is not A/B partitioned") -> isAbDevice = false
                    line.contains("Current slot:") -> currentSlot = line.substringAfter("Current slot:").trim()
                    line.contains("Other slot:") -> otherSlot = line.substringAfter("Other slot:").trim()
                }
            }

            SlotInfo(
                isAbDevice = isAbDevice,
                currentSlot = currentSlot,
                otherSlot = otherSlot
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get slot info", e)
            null
        }
    }

    /** Get partition list. */
    suspend fun getPartitionList(context: Context, slot: String?, scanAll: Boolean = false): List<PartitionInfo> = withContext(Dispatchers.IO) {
        try {
            val args = mutableListOf("flash", "list")
            if (slot != null) {
                args.add("--slot")
                args.add(slot)
            }
            if (scanAll) args.add("--all")

            val r = ReidClient.exec(context, args, timeoutMs = 30_000L)
            val lines = r.output.lines()
            val partitions = mutableListOf<PartitionInfo>()

            lines.forEach { line ->
                if (line.trim().startsWith("[") || line.contains("partitions")) return@forEach
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach

                try {
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    if (parts.size < 2) return@forEach

                    val name = parts[0]
                    val info = parts[1]
                    val isDangerous = info.contains("[DANGEROUS]")
                    val typeMatch = Regex("\\[(.*?),\\s*(\\d+)\\s*bytes\\]").find(info) ?: return@forEach

                    val type = typeMatch.groupValues[1]
                    val size = typeMatch.groupValues[2].toLongOrNull() ?: 0L
                    val isLogical = type == "logical"
                    val excludeFromBatch = name == "userdata" || name == "data"

                    val blockDevice = getPartitionBlockDevice(context, name, slot)

                    partitions.add(
                        PartitionInfo(
                            name = name,
                            blockDevice = blockDevice,
                            type = type,
                            size = size,
                            isLogical = isLogical,
                            isDangerous = isDangerous,
                            excludeFromBatch = excludeFromBatch
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse partition line: $line", e)
                }
            }

            partitions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get partition list", e)
            emptyList()
        }
    }

    private suspend fun getPartitionBlockDevice(context: Context, partition: String, slot: String?): String {
        val args = mutableListOf("flash", "info", partition)
        if (slot != null) {
            args.add("--slot")
            args.add(slot)
        }
        val r = ReidClient.exec(context, args, timeoutMs = 10_000L)
        r.output.lines().forEach { line ->
            if (line.contains("Block device:")) {
                return line.substringAfter("Block device:").trim()
            }
        }
        return ""
    }

    /** Backup partition. */
    suspend fun backupPartition(
        context: Context,
        partition: String,
        outputPath: String,
        slot: String?,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val args = mutableListOf("flash", "backup", partition, outputPath)
            if (slot != null) {
                args.add("--slot")
                args.add(slot)
            }
            val r = ReidClient.exec(context, args, timeoutMs = 300_000L)
            r.output.lines().forEach { onStdout(it) }
            if (r.exitCode != 0) onStderr(r.output)
            r.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup partition", e)
            onStderr("Error: ${e.message}")
            false
        }
    }

    /** Flash partition. */
    suspend fun flashPartition(
        context: Context,
        imagePath: String,
        partition: String,
        slot: String?,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val args = mutableListOf("flash", "image", imagePath, partition)
            if (slot != null) {
                args.add("--slot")
                args.add(slot)
            }
            val r = ReidClient.exec(context, args, timeoutMs = 120_000L)
            r.output.lines().forEach { onStdout(it) }
            if (r.exitCode != 0) onStderr(r.output)
            r.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flash partition", e)
            onStderr("Error: ${e.message}")
            false
        }
    }

    /** Map logical partitions (inactive slot). */
    suspend fun mapLogicalPartitions(
        context: Context,
        slot: String,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = ReidClient.exec(context, listOf("flash", "map", slot), timeoutMs = 60_000L)
            r.output.lines().forEach { onStdout(it) }
            if (r.exitCode != 0) onStderr(r.output)
            r.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to map logical partitions", e)
            onStderr("Error: ${e.message}")
            false
        }
    }
}
