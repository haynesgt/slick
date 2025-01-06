package com.haynesgt.slick

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.Matrix as GLMatrix

data class Vector2D(val x: Float, val y: Float)

@RequiresApi(Build.VERSION_CODES.Q)
class LowLatencyWhiteboardFeature(context: SurfaceView) : DrawingFeature {
    private var program: Int
    private val renderer: GLFrontBufferedRenderer<Vector2D>

    init {
        // Compile shaders and link program once
        val vertexShaderCode =
            "attribute vec4 vPosition;\n" +
                    "uniform mat4 uMVPMatrix;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVPMatrix * vPosition;\n" +
                    "}"
        val fragmentShaderCode =
            "precision mediump float;\n" +
                    "uniform vec4 vColor;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = vColor;\n" +
                    "}"
        program = -1

        // Initialize the renderer
        renderer = GLFrontBufferedRenderer(
            context,
            object : GLFrontBufferedRenderer.Callback<Vector2D> {
                override fun onDrawFrontBufferedLayer(
                    eglManager: EGLManager,
                    width: Int,
                    height: Int,
                    bufferInfo: BufferInfo,
                    transform: FloatArray,
                    param: Vector2D
                ) {
                    if (program == -1) {
                        // a program must be compiled in the same thread that uses it
                        program = createProgram(vertexShaderCode, fragmentShaderCode)
                    }
                    drawTriangleAtPosition(param.x, param.y, width, height)
                }

                override fun onDrawMultiBufferedLayer(
                    eglManager: EGLManager,
                    width: Int,
                    height: Int,
                    bufferInfo: BufferInfo,
                    transform: FloatArray,
                    params: Collection<Vector2D>
                ) {
                    // No-op for this implementation
                }
            }
        )
    }

    private fun drawTriangleAtPosition(x: Float, y: Float, width: Int, height: Int) {
        // Set up OpenGL ES environment
        GLES20.glViewport(0, 0, width, height)
        //GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f) // White background
        //GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Define vertices
        val vertices = floatArrayOf(
            0.0f, 0.0f, 0.0f, // Top
            -20f / width, -20f / height, 0.0f, // Bottom left
            20f / width, -20f / height , 0.0f // Bottom right
        )

        // Create translation matrix
        val translationMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(translationMatrix, 0)
        android.opengl.Matrix.translateM(
            translationMatrix, 0,
            x * 1.0f / width * 2 - 1, // Convert to normalized device coordinates
            (y * 1.0f / height * 2 - 1),
            0f
        )

        // Use the precompiled program
        GLES20.glUseProgram(program)

        // Pass the translation matrix to the shader
        val matrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, translationMatrix, 0)

        // Prepare vertex data
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

        // Set color
        val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
        GLES20.glUniform4fv(colorHandle, 1, floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f), 0)

        // Draw the triangle
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        // Disable the attribute
        //GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e("OpenGL", "Error linking program: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }

        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("OpenGL", "Error compiling shader: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    override fun render(event: MotionEvent) {
        val xuRelativeToSurface = event.x
        val yuRelativeToSurface = event.y
        renderer.renderFrontBufferedLayer(Vector2D(xuRelativeToSurface, yuRelativeToSurface))
    }

    override fun commit() {
        renderer.commit()
    }
}
