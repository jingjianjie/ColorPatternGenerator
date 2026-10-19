package com.example.colorpatterngenerator

import java.math.BigInteger

/** Numeric stable release tags, including the shortened version names used by this app. */
internal data class ReleaseVersion private constructor(private val components: List<BigInteger>) :
    Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(components.size, other.components.size)) {
            val comparison = (components.getOrNull(index) ?: BigInteger.ZERO)
                .compareTo(other.components.getOrNull(index) ?: BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private val stableTag = Regex(
            "^[vV]?((?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))*)" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$"
        )

        fun parseOrNull(value: String): ReleaseVersion? {
            if (value.length > 128) return null
            val match = stableTag.matchEntire(value) ?: return null
            val components = match.groupValues[1].split('.').map(::BigInteger).toMutableList()
            while (components.size > 1 && components.last() == BigInteger.ZERO) {
                components.removeAt(components.lastIndex)
            }
            return ReleaseVersion(components)
        }
    }
}
