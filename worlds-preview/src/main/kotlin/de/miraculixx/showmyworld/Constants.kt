package de.miraculixx.showmyworld

import de.miraculixx.common.ModScope

object Constants {
    const val MOD_ID = "showmyworld"

    private val mod = ModScope(MOD_ID, "Show My World")

    val LOG = mod.log

    /** Background scope for the panorama IO */
    val SCOPE = mod.scope
}
