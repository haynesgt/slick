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
//import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.tooling.data.position
import androidx.graphics.lowlatency.BufferInfo
import androidx.graphics.lowlatency.GLFrontBufferedRenderer
import androidx.graphics.opengl.egl.EGLManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.Matrix as Matrix

@RequiresApi(Build.VERSION_CODES.Q)
class LowLatencyWhiteboardFeature(context: SurfaceView) : DrawingFeature {
    private val renderer: GLFrontBufferedRenderer<MotionEvent> = GLFrontBufferedRenderer(
        context,
        object : GLFrontBufferedRenderer.Callback<MotionEvent> {

            override fun onDrawFrontBufferedLayer(
                eglManager: EGLManager,
                width: Int,
                height: Int,
                bufferInfo: BufferInfo,
                transform: FloatArray,
                param: MotionEvent
            ) {
                // 1. Set up OpenGL ES environment
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f) // White background
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // 2. Define vertices and shaders
                val vertices = floatArrayOf(
                    0.0f+param.x/100.9f, 0.5f, 0.0f, // Top
                    -0.5f, -0.5f, 0.0f, // Bottom left
                    0.5f, -0.5f, 0.0f // Bottom right
                )
                val vertexShaderCode =
                    "attribute vec4 vPosition;" +
                            "void main() {" +
                            "  gl_Position = vPosition;" +
                            "}"
                val fragmentShaderCode =
                    "precision mediump float;" +
                            "uniform vec4 vColor;" +
                            "void main() {" +
                            "  gl_FragColor = vColor;" +
                            "}"
                val program = createProgram(vertexShaderCode, fragmentShaderCode)
                GLES20.glUseProgram(program)

                // translate to param position
                val translationHandle = GLES20.glGetUniformLocation(program, "translation")
                val translationMatrix = FloatArray(16)
                Matrix.translateM(translationMatrix, 0, param.x, param.y, 0f)
                GLES20.glUniformMatrix2fv(translationHandle, 1, false, translationMatrix, 0)
                // Pass translation matrix to shader
                val matrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
                GLES20.glUniformMatrix4fv(matrixHandle, 1, false, translationMatrix, 0)

                // 3. Enable and bind attributes
                val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4) // 4 bytes per float
                .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(vertices)
                vertexBuffer.position(0)
                GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)

                val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
                GLES20.glEnableVertexAttribArray(positionHandle)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer)
                // translate to param position using vPosition
                //GLES20.glVertexAttrib3f(positionHandle, param.x, param.y, 0f)



                // 4. Draw the triangle
                val colorHandle = GLES20.glGetUniformLocation(program, "vColor")
                GLES20.glUniform4fv(colorHandle, 1, floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f), 0) // Blue color
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

            }

            private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
                val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
                val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, vertexShader)
                GLES20.glAttachShader(program, fragmentShader)
                GLES20.glLinkProgram(program)

                // Check for linking errors
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

                // Check for compilation errors
                val compiled = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    Log.e("OpenGL", "Error compiling shader: ${GLES20.glGetShaderInfoLog(shader)}")
                    GLES20.glDeleteShader(shader)
                    return 0
                }

                return shader
            }

            override fun onDrawMultiBufferedLayer(
                eglManager: EGLManager,
                width: Int,
                height: Int,
                bufferInfo: BufferInfo,
                transform: FloatArray,
                params: Collection<MotionEvent>
            ) {
                //inputs.forEach { drawStroke(canvas, it) }
            }
        }
    )

    override fun render(event: MotionEvent) {
        renderer.renderFrontBufferedLayer(event)
    }

    override fun commit() {
        renderer.commit()
    }
}