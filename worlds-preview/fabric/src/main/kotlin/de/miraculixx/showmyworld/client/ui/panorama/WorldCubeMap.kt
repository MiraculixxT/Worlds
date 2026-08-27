package de.miraculixx.showmyworld.client.ui.panorama

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import de.miraculixx.showmyworld.Constants
import java.util.OptionalDouble
import java.util.OptionalInt
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * Copy of [net.minecraft.client.renderer.CubeMap] with alpha option and variable texture
 */
class WorldCubeMap : AutoCloseable {
    private val projectionMatrixUbo = CachedPerspectiveProjectionMatrixBuffer("worlds panorama", Z_NEAR, Z_FAR)
    private val vertexBuffer = initializeVertices()

    fun render(location: Identifier, rotXInDegrees: Float, rotYInDegrees: Float, alpha: Float) {
        val minecraft = Minecraft.getInstance()
        val mainRenderTarget = minecraft.mainRenderTarget
        val colorTexture = mainRenderTarget.colorTextureView ?: return
        val texture = minecraft.textureManager.getTexture(location)
        val window = minecraft.window
        RenderSystem.setProjectionMatrix(
            projectionMatrixUbo.getBuffer(window.width, window.height, FOV), ProjectionType.PERSPECTIVE,
        )
        val indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)
        val indexBuffer = indices.getBuffer(36)
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        modelViewStack.rotationX(Math.PI.toFloat())
        modelViewStack.rotateX(rotXInDegrees * DEG_TO_RAD)
        modelViewStack.rotateY(rotYInDegrees * DEG_TO_RAD)
        val dynamicTransforms = RenderSystem.getDynamicUniforms()
            .writeTransform(Matrix4f(modelViewStack), Vector4f(1f, 1f, 1f, alpha), Vector3f(), Matrix4f())
        modelViewStack.popMatrix()

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val label = { "Worlds panorama" }
        val depth = mainRenderTarget.depthTextureView
        // The pipeline never writes depth, so the attachment is only along for the ride.
        val pass = if (depth != null) {
            encoder.createRenderPass(label, colorTexture, OptionalInt.empty(), depth, OptionalDouble.empty())
        } else {
            encoder.createRenderPass(label, colorTexture, OptionalInt.empty())
        }
        pass.use { renderPass ->
            renderPass.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(renderPass)
            renderPass.setVertexBuffer(0, vertexBuffer)
            renderPass.setIndexBuffer(indexBuffer, indices.type())
            renderPass.setUniform("DynamicTransforms", dynamicTransforms)
            renderPass.bindTexture("Sampler0", texture.textureView, texture.sampler)
            renderPass.drawIndexed(0, 0, 36, 1)
        }
    }

    override fun close() {
        vertexBuffer.close()
        projectionMatrixUbo.close()
    }

    private companion object {
        const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
        const val Z_NEAR = 0.05f
        const val Z_FAR = 10f
        const val FOV = 85f

        val PIPELINE: RenderPipeline = RenderPipeline.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withLocation(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pipeline/world_panorama"))
            .withVertexShader(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/world_panorama"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/world_panorama"))
            .withSampler("Sampler0")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .withColorWrite(true, false)
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .build()

        val CUBE = floatArrayOf(
            -1f, -1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f,
            1f, -1f, 1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f, -1f, -1f,
            1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f, -1f, -1f, -1f,
            -1f, -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f,
            -1f, -1f, -1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, -1f,
            -1f, 1f, 1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f,
        )

        fun initializeVertices(): GpuBuffer =
            ByteBufferBuilder.exactlySized(DefaultVertexFormat.POSITION.vertexSize * 4 * 6).use { byteBufferBuilder ->
                val bufferBuilder = BufferBuilder(byteBufferBuilder, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
                for (i in CUBE.indices step 3) bufferBuilder.addVertex(CUBE[i], CUBE[i + 1], CUBE[i + 2])
                bufferBuilder.buildOrThrow().use { meshData ->
                    RenderSystem.getDevice().createBuffer(
                        { "Worlds panorama vertex buffer" }, GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer(),
                    )
                }
            }
    }
}
