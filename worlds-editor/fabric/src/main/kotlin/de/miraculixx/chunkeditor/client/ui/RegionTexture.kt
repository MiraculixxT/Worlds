package de.miraculixx.chunkeditor.client.ui

import com.mojang.blaze3d.platform.NativeImage
import de.miraculixx.chunkeditor.data.RegionPixels
import net.minecraft.core.Registry
import net.minecraft.world.level.biome.Biome

internal object RegionTexture {

    fun image(pixels: RegionPixels, biomes: Registry<Biome>?): NativeImage {
        val size = pixels.size
        val image = NativeImage(NativeImage.Format.RGBA, size, size, false)
        val tint = biomes?.let { RegionTint.of(pixels.tintPalette, it) }
        val index = pixels.tintIndex
        val argb = pixels.argb
        // 1.21 keeps the image buffer private, so there is no bulk write past setPixelRGBA
        if (tint == null || index == null) {
            for (i in argb.indices) image.setPixelRGBA(i % size, i / size, abgr(argb[i]))
        } else {
            for (i in argb.indices) image.setPixelRGBA(i % size, i / size, abgr(tint.apply(argb[i], index[i].toInt())))
        }
        return image
    }

    /** 0xAARRGGBB → the 0xAABBGGRR [NativeImage.setPixelRGBA] wants */
    private fun abgr(argb: Int): Int =
        (argb and -0x1000000) or (argb and 0xFF shl 16) or (argb and 0xFF00) or (argb ushr 16 and 0xFF)
}
