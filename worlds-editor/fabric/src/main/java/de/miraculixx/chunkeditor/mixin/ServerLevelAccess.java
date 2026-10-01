package de.miraculixx.chunkeditor.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Entity storage, to wait for its pending writes (see ServerBackend.awaitWrites) */
@Mixin(ServerLevel.class)
public interface ServerLevelAccess {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> chunkeditorEntityManager();
}
