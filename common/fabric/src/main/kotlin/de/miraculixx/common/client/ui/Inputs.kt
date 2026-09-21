package de.miraculixx.common.client.ui

import com.mojang.blaze3d.platform.InputConstants

/**
 * Vanilla's `CommonInputs.selected` counts the space bar as well, which a text field needs for
 * itself - so anything committing an edit asks this instead.
 */
fun isEnter(keyCode: Int): Boolean =
    keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER
