package de.miraculixx.worlds.data

import de.miraculixx.worlds.Constants
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.world.item.Items


object ItemComponents {

    fun bound(): Boolean = Items.STONE.builtInRegistryHolder().areComponentsBound()

    /**
     * Bind them if they are not, then run [onDone] on the render thread.
     * Binds vanilla component to render items outside of worlds.
     */
    fun prepare(onDone: (Boolean) -> Unit) {
        if (bound()) {
            onDone(true)
            return
        }
        Constants.SCOPE.launch {
            val pending = try {
                BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup())
            } catch (e: Exception) {
                Constants.LOG.error("Could not build item data components", e)
                Minecraft.getInstance().execute { onDone(false) }
                return@launch
            }
            Minecraft.getInstance().execute {
                pending.forEach { it.apply() }
                onDone(true)
            }
        }
    }
}
