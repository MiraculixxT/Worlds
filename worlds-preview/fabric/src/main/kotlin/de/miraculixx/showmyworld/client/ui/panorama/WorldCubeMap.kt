package de.miraculixx.showmyworld.client.ui.panorama

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import de.miraculixx.showmyworld.Constants
import java.util.Optional
import java.util.OptionalDouble
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector4f

/**
 * Copy of [net.minecraft.client.renderer.CubeMap] with alpha option and variable texture
 */
class WorldCubeMap : AutoCloseable {
    private val projection = Projection()
    private val projectionMatrixUbo = ProjectionMatrixBuffer("worlds panorama")
    private val vertexBuffer = initializeVertices()

    fun render(location: Identifier, rotXInDegrees: Float, rotYInDegrees: Float, alpha: Float) {
        val minecraft = Minecraft.getInstance()
        val mainRenderTarget = minecraft.gameRenderer.mainRenderTarget()
        val colorTexture = mainRenderTarget.colorTextureView ?: return
        val texture = minecraft.textureManager.getTexture(location)
        val windowState = minecraft.gameRenderer.gameRenderState().windowRenderState
        projection.setupPerspective(0.05f, 10f, 85f, windowState.width.toFloat(), windowState.height.toFloat())
        RenderSystem.setProjectionMatrix(projectionMatrixUbo.getBuffer(projection), ProjectionType.PERSPECTIVE)
        val indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        val indexBuffer = indices.getBuffer(36)
        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        modelViewStack.rotationX(Math.PI.toFloat())
        modelViewStack.rotateX(rotXInDegrees * DEG_TO_RAD)
        modelViewStack.rotateY(rotYInDegrees * DEG_TO_RAD)
        val dynamicTransforms = RenderSystem.getDynamicUniforms()
            .writeTransform(Matrix4f(modelViewStack), Vector4f(1f, 1f, 1f, alpha))
        modelViewStack.popMatrix()

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val label = { "Worlds panorama" }
        val depth = mainRenderTarget.depthTextureView
        // The pipeline declares no depth state, so the attachment is only along for the ride.
        val pass = if (depth != null) {
            encoder.createRenderPass(label, colorTexture, Optional.empty(), depth, OptionalDouble.empty())
        } else {
            encoder.createRenderPass(label, colorTexture, Optional.empty())
        }
        pass.use { renderPass ->
            renderPass.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(renderPass)
            renderPass.setVertexBuffer(0, vertexBuffer.slice())
            renderPass.setIndexBuffer(indexBuffer, indices.type())
            renderPass.setUniform("DynamicTransforms", dynamicTransforms)
            renderPass.bindTexture("Sampler0", texture.textureView, texture.sampler)
            renderPass.drawIndexed(36, 1, 0, 0, 0)
        }
    }

    override fun close() {
        vertexBuffer.close()
        projectionMatrixUbo.close()
    }

    private companion object {
        const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

        val PIPELINE: RenderPipeline = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withLocation(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "pipeline/world_panorama"))
            .withVertexShader(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/world_panorama"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "core/world_panorama"))
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexBinding(0, DefaultVertexFormat.POSITION)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
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
                val bufferBuilder = BufferBuilder(byteBufferBuilder, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION)
                for (i in CUBE.indices step 3) bufferBuilder.addVertex(CUBE[i], CUBE[i + 1], CUBE[i + 2])
                bufferBuilder.buildOrThrow().use { meshData ->
                    RenderSystem.getDevice().createBuffer(
                        { "Worlds panorama vertex buffer" }, GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer(),
                    )
                }
            }
    }
}
