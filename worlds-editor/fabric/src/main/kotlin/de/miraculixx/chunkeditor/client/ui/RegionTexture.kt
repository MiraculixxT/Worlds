package de.miraculixx.chunkeditor.client.ui

import com.mojang.blaze3d.platform.NativeImage
import de.miraculixx.chunkeditor.data.RegionPixels
import net.minecraft.core.Registry
import net.minecraft.world.level.biome.Biome
import org.lwjgl.system.MemoryUtil

internal object RegionTexture {

    fun image(pixels: RegionPixels, biomes: Registry<Biome>?): NativeImage {
        val size = pixels.size
        val image = NativeImage(NativeImage.Format.RGBA, size, size, false)
        val tint = biomes?.let { RegionTint.of(pixels.tintPalette, it) }
        val index = pixels.tintIndex
        // setPixelABGR per pixel costs ~250k bounds and format checks for detailed regions
        val out = MemoryUtil.memIntBuffer(image.pointer, size * size)
        val argb = pixels.argb
        if (tint == null || index == null) {
            for (i in argb.indices) out.put(i, abgr(argb[i]))
        } else {
            for (i in argb.indices) out.put(i, abgr(tint.apply(argb[i], index[i].toInt())))
        }
        return image
    }

    /** 0xAARRGGBB → 0xAABBGGRR [NativeImage.setPixelABGR] wants */
    private fun abgr(argb: Int): Int =
        (argb and -0x1000000) or (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb ushr 16 and 0xFF)
}
