package com.example.colorpatterngenerator

sealed class Command {
    data class SetColor(val r: Int, val g: Int, val b: Int, val brightness: Float? = null) : Command()
    data class SetCircleColor(val r: Int, val g: Int, val b: Int, val brightness: Float? = null) : Command()
    object BasicConfig : Command()
    object StreamStart : Command()
    object StreamStop : Command()
    data class ImageHeader(val name: String, val size: Int) : Command()
    object GetDiskProperty : Command()
    data class SetDiskProperty(val diameterMm: Float, val centerX: Float, val centerY: Float) : Command()
    object GetPositionAngle : Command()
    object GetStatus : Command()
    object GetBrightnessSettleMs : Command()
    data class SetBrightnessSettleMs(val delayMs: Long) : Command()
    object InvalidBrightnessSettleMs : Command()
    object SetPositionMode : Command()
    object Sleep : Command()
    object Wake : Command()
    object ResetPos : Command()
    object Lock : Command()
    object Unlock : Command()
    object Unknown : Command()
}

class CommandParser {
    fun parse(cmd: String): Command {
        return when {
            cmd == "sleep" -> Command.Sleep
            cmd == "wake" -> Command.Wake
            cmd == "reset_pos" -> Command.ResetPos
            cmd == "lock" -> Command.Lock
            cmd == "unlock" -> Command.Unlock
            cmd == "get_brightness_settle_ms" -> Command.GetBrightnessSettleMs
            cmd.startsWith("set_brightness_settle_ms") -> {
                val delayMs = if (cmd.startsWith("set_brightness_settle_ms:")) {
                    cmd.substringAfter(":").toLongOrNull()
                } else null
                if (delayMs != null && delayMs in 0L..60_000L) {
                    Command.SetBrightnessSettleMs(delayMs)
                } else {
                    Command.InvalidBrightnessSettleMs
                }
            }
            cmd.startsWith("color=") -> {
                val parts = cmd.substringAfter("=").split(",")
                if (parts.size >= 3) {
                    val r = parts[0].toIntOrNull() ?: 0
                    val g = parts[1].toIntOrNull() ?: 0
                    val b = parts[2].toIntOrNull() ?: 0
                    val suppliedBrightness = if (parts.size >= 4) parts[3].toFloatOrNull() else null
                    if (suppliedBrightness != null && !suppliedBrightness.isFinite()) return Command.Unknown
                    val brightness = suppliedBrightness?.coerceIn(0.01f, 100.0f)
                    Command.SetColor(r, g, b, brightness)
                } else Command.Unknown
            }
            cmd.startsWith("color_o=") -> {
                val parts = cmd.substringAfter("=").split(",")
                if (parts.size >= 3) {
                    val r = parts[0].toIntOrNull() ?: 0
                    val g = parts[1].toIntOrNull() ?: 0
                    val b = parts[2].toIntOrNull() ?: 0
                    val suppliedBrightness = if (parts.size >= 4) parts[3].toFloatOrNull() else null
                    if (suppliedBrightness != null && !suppliedBrightness.isFinite()) return Command.Unknown
                    val brightness = suppliedBrightness?.coerceIn(0.01f, 100.0f)
                    Command.SetCircleColor(r, g, b, brightness)
                } else Command.Unknown
            }
            cmd == "get_disk_property" -> Command.GetDiskProperty
            cmd.startsWith("set_disk_property:") -> {
                val parts = cmd.substringAfter(":").split(",")
                if (parts.size == 3) {
                    try {
                        Command.SetDiskProperty(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
                    } catch (e: Exception) {
                        Command.Unknown
                    }
                } else {
                    Command.Unknown
                }
            }
            cmd == "get_position_angle" -> Command.GetPositionAngle
            cmd == "get_status" -> Command.GetStatus
            cmd == "set_position" -> Command.SetPositionMode
            cmd == "basic config" -> Command.BasicConfig
            cmd == "stream_start" -> Command.StreamStart
            cmd == "stream_stop" -> Command.StreamStop
            cmd.startsWith("image:") -> {
                val parts = cmd.split(":")
                if (parts.size >= 3) {
                    try {
                        Command.ImageHeader(parts[1], parts[2].toInt())
                    } catch (e: Exception) {
                        Command.Unknown
                    }
                } else {
                    Command.Unknown
                }
            }
            else -> Command.Unknown
        }
    }
}
