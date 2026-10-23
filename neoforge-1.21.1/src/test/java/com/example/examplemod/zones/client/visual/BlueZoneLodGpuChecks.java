package com.example.examplemod.zones.client.visual;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Exercises the actual shipped shaders in a hidden OpenGL window without loading Minecraft. */
public final class BlueZoneLodGpuChecks {
    private static final int WIDTH = 16, HEIGHT = 12;
    private static final double MAIN_NEAR = 0.05, MAIN_FAR = 256;
    private static final double LOD_NEAR = 8, LOD_FAR = 48_000;
    private static final float SATURATED_DEPTH = (float) (1.0 - 1.0 / 16_777_215.0);
    private static final int[] BACKGROUND = {8, 16, 24, 255};
    private static final int[] CURTAIN = {32, 128, 240, 255};
    private static int checks, cases;
    private static int program, framebuffer, mainDepth, colorTexture, lodDepth, vao, vbo;

    public static void main(String[] args) throws Exception {
        Path shaderDirectory = args.length == 0
                ? Path.of("src/main/resources/assets/tacz_pubg/shaders/core") : Path.of(args[0]);
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err).set();
        long window = NULL;
        boolean initialized = false;
        try {
            if (!glfwInit()) throw new IllegalStateException("Hidden OpenGL context unavailable: glfwInit failed");
            initialized = true;
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(WIDTH, HEIGHT, "Blue-zone offline GPU checks", NULL, NULL);
            if (window == NULL) throw new IllegalStateException("Hidden OpenGL context unavailable: glfwCreateWindow failed");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            System.out.println("Hidden GPU context: " + glGetString(GL_VENDOR) + " / "
                    + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
            program = link(Files.readString(shaderDirectory.resolve("blue_zone_lod.vsh")),
                    Files.readString(shaderDirectory.resolve("blue_zone_lod.fsh")));
            createTarget();
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 7 * Float.BYTES, 0);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, 7 * Float.BYTES, 3L * Float.BYTES);

            uniformCase("LOD behind curtain, within native far plane", 100, 200, 1, true);
            uniformCase("LOD in front of curtain, within native far plane", 100, 50, 1, false);
            uniformCase("equal LOD and curtain depth", 100, 100, 1, true);
            sentinelCase("native depth zero mask defers to clear main depth", 100, 0, 1, true);
            sentinelCase("sky depth one defers to clear main depth", 100, 1, 1, true);
            sentinelCase("native depth zero mask preserves nearby main occluder", 100, 0,
                    projectedDepth(32, MAIN_NEAR, MAIN_FAR), false);
            sentinelCase("sky depth one preserves nearby main occluder", 100, 1,
                    projectedDepth(32, MAIN_NEAR, MAIN_FAR), false);
            uniformCase("beyond native far plane, LOD behind, main has no LOD", 2_000, 4_000, 1, true);
            uniformCase("beyond native far plane, LOD in front, main has no LOD", 2_000, 1_000, 1, false);
            uniformCase("beyond native far plane, LOD behind, main depth saturated", 2_000, 4_000,
                    SATURATED_DEPTH, true);
            uniformCase("beyond native far plane, LOD in front, main depth saturated", 2_000, 1_000,
                    SATURATED_DEPTH, false);
            uniformCase("nearby native geometry hides far curtain in front of distant LOD", 2_000, 4_000,
                    projectedDepth(32, MAIN_NEAR, MAIN_FAR), false);
            sentinelCase("far curtain with sky sentinel and saturated main depth", 2_000, 1,
                    SATURATED_DEPTH, true);
            sentinelCase("far curtain with native mask and nearby native geometry", 2_000, 0,
                    projectedDepth(32, MAIN_NEAR, MAIN_FAR), false);
            scaledCase("half resolution LOD depth", 8, 6);
            scaledCase("non-integer resolution ratio LOD depth", 9, 7);
            scaledCase("double resolution LOD depth", 32, 24);
            check(glGetError() == GL_NO_ERROR, "OpenGL reports no errors after all cases");
            System.out.println("Blue-zone actual-shader GPU checks passed: " + cases + " cases, " + checks
                    + " assertions (including every output pixel and unchanged input depth texels)");
        } finally {
            if (window != NULL) {
                if (program != 0) glDeleteProgram(program);
                if (vbo != 0) glDeleteBuffers(vbo);
                if (vao != 0) glDeleteVertexArrays(vao);
                if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
                glDeleteTextures(new int[]{mainDepth, colorTexture, lodDepth});
                glfwDestroyWindow(window);
            }
            if (initialized) glfwTerminate();
            callback.free();
        }
    }

    private static void uniformCase(String name, double curtainDistance, double sceneDistance,
                                    float mainSceneDepth, boolean visible) {
        sentinelCase(name, curtainDistance, projectedDepth(sceneDistance, LOD_NEAR, LOD_FAR), mainSceneDepth, visible);
    }

    private static void sentinelCase(String name, double curtainDistance, float lodSceneDepth,
                                     float mainSceneDepth, boolean visible) {
        float[] lod = filled(WIDTH * HEIGHT, lodSceneDepth);
        boolean[] expected = new boolean[WIDTH * HEIGHT];
        Arrays.fill(expected, visible);
        runCase(name, curtainDistance, filled(WIDTH * HEIGHT, mainSceneDepth), WIDTH, HEIGHT, lod, expected);
    }

    private static void scaledCase(String name, int lodWidth, int lodHeight) {
        float[] lod = new float[lodWidth * lodHeight];
        boolean[] expected = new boolean[WIDTH * HEIGHT];
        for (int y = 0; y < lodHeight; y++) {
            for (int x = 0; x < lodWidth; x++) {
                lod[y * lodWidth + x] = projectedDepth(((x / 3 + y / 2) & 1) == 0 ? 1_000 : 4_000,
                        LOD_NEAR, LOD_FAR);
            }
        }
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int lx = (int) ((x + 0.5) / WIDTH * lodWidth);
                int ly = (int) ((y + 0.5) / HEIGHT * lodHeight);
                expected[y * WIDTH + x] = ((lx / 3 + ly / 2) & 1) != 0;
            }
        }
        runCase(name, 2_000, filled(WIDTH * HEIGHT, SATURATED_DEPTH), lodWidth, lodHeight, lod, expected);
    }

    private static void runCase(String name, double distance, float[] mainData,
                                int lodWidth, int lodHeight, float[] lodData, boolean[] expected) {
        uploadDepth(mainDepth, WIDTH, HEIGHT, mainData);
        uploadDepth(lodDepth, lodWidth, lodHeight, lodData);
        float[] nativeBefore = readDepth(mainDepth, WIDTH, HEIGHT);
        float[] lodBefore = readDepth(lodDepth, lodWidth, lodHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, name + ": complete framebuffer");
        glViewport(0, 0, WIDTH, HEIGHT);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glColorMask(true, true, true, true);
        glClearColor(BACKGROUND[0] / 255f, BACKGROUND[1] / 255f, BACKGROUND[2] / 255f, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glDepthRange(0, 1);
        glUseProgram(program);
        matrix("ModelViewMat", new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        matrix("ProjMat", projection(MAIN_NEAR, MAIN_FAR));
        matrix("LodTransform", projection(LOD_NEAR, LOD_FAR));
        glUniform2f(uniform("MainTargetSize"), WIDTH, HEIGHT);
        glUniform4f(uniform("ColorModulator"), 1, 1, 1, 1);
        glUniform1i(uniform("LodDepth"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, 0);
        glBindTexture(GL_TEXTURE_2D, lodDepth);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        float d = (float) distance;
        float[] vertices = new float[6 * 7];
        float[][] corners = {{-d, -d}, {d, -d}, {d, d}, {-d, -d}, {d, d}, {-d, d}};
        for (int vertex = 0; vertex < 6; vertex++) {
            int offset = vertex * 7;
            vertices[offset] = corners[vertex][0];
            vertices[offset + 1] = corners[vertex][1];
            vertices[offset + 2] = -d;
            vertices[offset + 3] = CURTAIN[0] / 255f;
            vertices[offset + 4] = CURTAIN[1] / 255f;
            vertices[offset + 5] = CURTAIN[2] / 255f;
            vertices[offset + 6] = 1;
        }
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int[] wanted = expected[y * WIDTH + x] ? CURTAIN : BACKGROUND;
                for (int channel = 0; channel < 4; channel++) {
                    int actual = Byte.toUnsignedInt(pixels.get((y * WIDTH + x) * 4 + channel));
                    check(Math.abs(actual - wanted[channel]) <= 1, name + ": pixel " + x + "," + y
                            + " channel " + channel + " expected " + wanted[channel] + " actual " + actual);
                }
            }
        }
        check(Arrays.equals(nativeBefore, readDepth(mainDepth, WIDTH, HEIGHT)), name + ": main depth is unchanged");
        check(Arrays.equals(lodBefore, readDepth(lodDepth, lodWidth, lodHeight)), name + ": LOD depth is unchanged");
        check(glGetError() == GL_NO_ERROR, name + ": no OpenGL errors");
        cases++;
        System.out.println("PASS " + name);
    }

    private static float[] filled(int count, float value) {
        float[] data = new float[count];
        Arrays.fill(data, value);
        return data;
    }

    private static float projectedDepth(double distance, double near, double far) {
        return (float) (far / (far - near) - far * near / ((far - near) * distance));
    }

    private static float[] projection(double near, double far) {
        return new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0,
                (float) ((far + near) / (near - far)), -1,
                0, 0, (float) (2 * far * near / (near - far)), 0};
    }

    private static void createTarget() {
        framebuffer = glGenFramebuffers();
        mainDepth = glGenTextures();
        colorTexture = glGenTextures();
        lodDepth = glGenTextures();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        uploadDepth(mainDepth, WIDTH, HEIGHT, filled(WIDTH * HEIGHT, 1));
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, mainDepth, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
    }

    private static void uploadDepth(int texture, int width, int height, float[] data) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, data);
    }

    private static float[] readDepth(int texture, int width, int height) {
        glBindTexture(GL_TEXTURE_2D, texture);
        float[] data = new float[width * height];
        glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, data);
        return data;
    }

    private static int link(String vertexSource, String fragmentSource) {
        int vertex = compile(GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
        int linked = glCreateProgram();
        try {
            glAttachShader(linked, vertex);
            glAttachShader(linked, fragment);
            glBindAttribLocation(linked, 0, "Position");
            glBindAttribLocation(linked, 1, "Color");
            glLinkProgram(linked);
            if (glGetProgrami(linked, GL_LINK_STATUS) == GL_FALSE) {
                throw new IllegalStateException("Actual shader link failed: " + glGetProgramInfoLog(linked));
            }
            return linked;
        } finally {
            glDeleteShader(vertex);
            glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("Actual shader compile failed: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static void matrix(String name, float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(16 * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).flip();
        glUniformMatrix4fv(uniform(name), false, buffer);
    }

    private static int uniform(String name) {
        int location = glGetUniformLocation(program, name);
        if (location < 0) throw new IllegalStateException("Actual shader uniform missing: " + name);
        return location;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
