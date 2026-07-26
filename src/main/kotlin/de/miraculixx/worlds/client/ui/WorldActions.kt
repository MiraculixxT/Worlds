package de.miraculixx.worlds.client.ui

import de.miraculixx.worlds.Constants
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.AlertScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.NoticeWithLinkScreen
import net.minecraft.client.gui.screens.ProgressScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.validation.ContentValidationException
import java.io.IOException

/**
 * Vanilla world-management flows (delete / edit / recreate).
 * Original functions are wired into the original screen.
 */
object WorldActions {

    private val mc: Minecraft get() = Minecraft.getInstance()

    fun delete(folder: String, title: String, onDone: () -> Unit) {
        mc.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) {
                        mc.gui.setScreen(ProgressScreen(true))
                        doDelete(folder)
                    }
                    onDone()
                },
                Component.translatable("selectWorld.deleteQuestion"),
                Component.translatable("selectWorld.deleteWarning", title),
                Component.translatable("selectWorld.deleteButton"),
                CommonComponents.GUI_CANCEL,
            )
        )
    }

    private fun doDelete(folder: String) {
        try {
            mc.levelSource.createAccess(folder).use { it.deleteLevel() }
        } catch (e: IOException) {
            SystemToast.onWorldDeleteFailure(mc, folder)
            Constants.LOG.error("Failed to delete world {}", folder, e)
        }
    }

    fun edit(parent: Screen, folder: String, onDone: () -> Unit) {
        queueLoadScreen()
        val access = try {
            mc.levelSource.validateAndCreateAccess(folder)
        } catch (e: IOException) {
            SystemToast.onWorldAccessFailure(mc, folder)
            Constants.LOG.error("Failed to access level {}", folder, e)
            onDone()
            return
        } catch (e: ContentValidationException) {
            Constants.LOG.warn("{}", e.message)
            symlinkWarning(parent)
            return
        }
        val screen = try {
            EditWorldScreen.create(mc, access) {
                access.safeClose()
                onDone()
            }
        } catch (e: Exception) {
            access.safeClose()
            SystemToast.onWorldAccessFailure(mc, folder)
            Constants.LOG.error("Failed to load world data {}", folder, e)
            onDone()
            return
        }
        mc.gui.setScreen(screen)
    }

    fun recreate(parent: Screen, folder: String, onDone: () -> Unit) {
        queueLoadScreen()
        try {
            mc.levelSource.validateAndCreateAccess(folder).use { access ->
                val recreated = mc.createWorldOpenFlows().recreateWorldData(access)
                val levelSettings = recreated.first
                val context = recreated.second
                val dataPackDir = CreateWorldScreen.createTempDataPackDirFromExistingWorld(
                    access.getLevelPath(LevelResource.DATAPACK_DIR), mc,
                )
                context.validate()
                val create = {
                    CreateWorldScreen.createFromExisting(mc, { onDone() }, levelSettings, context, dataPackDir)
                }
                if (context.options().isOldCustomizedWorld) {
                    mc.gui.setScreen(
                        ConfirmScreen(
                            { proceed -> mc.gui.setScreen(if (proceed) create() else parent) },
                            Component.translatable("selectWorld.recreate.customized.title"),
                            Component.translatable("selectWorld.recreate.customized.text"),
                            CommonComponents.GUI_PROCEED,
                            CommonComponents.GUI_CANCEL,
                        )
                    )
                } else {
                    mc.gui.setScreen(create())
                }
            }
        } catch (e: ContentValidationException) {
            Constants.LOG.warn("{}", e.message)
            symlinkWarning(parent)
        } catch (e: Exception) {
            Constants.LOG.error("Unable to recreate world", e)
            mc.gui.setScreen(
                AlertScreen(
                    { mc.gui.setScreen(parent) },
                    Component.translatable("selectWorld.recreate.error.title"),
                    Component.translatable("selectWorld.recreate.error.text"),
                )
            )
        }
    }

    private fun symlinkWarning(parent: Screen) {
        mc.gui.setScreen(NoticeWithLinkScreen.createWorldSymlinkWarningScreen { mc.gui.setScreen(parent) })
    }

    private fun queueLoadScreen() {
        mc.setScreenAndShow(GenericMessageScreen(Component.translatable("selectWorld.data_read")))
    }
}
