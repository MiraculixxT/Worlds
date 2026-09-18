package de.miraculixx.chunkeditor

import de.miraculixx.common.ModScope

object Constants {
    const val MOD_ID = "chunkeditor"

    private val mod = ModScope(MOD_ID, "Chunk Editor")

    val LOG = mod.log

    /** Background scope for region file IO and rendering. */
    val SCOPE = mod.scope

    /** Milliseconds since a `System.nanoTime()` mark */
    fun ms(start: Long): Long = (System.nanoTime() - start) / 1_000_000
}
