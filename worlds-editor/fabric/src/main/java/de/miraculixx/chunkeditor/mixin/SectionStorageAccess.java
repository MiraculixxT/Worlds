package de.miraculixx.chunkeditor.mixin;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** POI storage */
@Mixin(SectionStorage.class)
public interface SectionStorageAccess {
    @Accessor("simpleRegionStorage")
    SimpleRegionStorage chunkeditorRegionStorage();
}
