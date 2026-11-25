# FSR shader check

Compiles every `fsr3fg/` shader the way Iris does at runtime, without a GPU.

1. `./gradlew assemble`, then build a classpath of `common/build/classes/java/main`, `common/build/resources/main`
   and the common compile classpath (Minecraft, LWJGL, glsl-transformer, fastutil, joml...).
2. `java -cp <classpath>:. FsrShaderPatch.java` (run from this folder): writes the OpenGL-patched sources to `gl/` and
   the Vulkan-patched sources to `vk/`, exactly as `TransformPatcher.patchProgram` produces them.
3. OpenGL, strict: `pip install moderngl glcontext`, then `python3 mesa_compile.py` compiles and links them with Mesa
   (EGL, llvmpipe GL 4.5 core). Mesa rejects the same non-portable GLSL NVIDIA does (for example reserved words).
4. Vulkan: `for f in vk/*; do glslangValidator -V --target-env vulkan1.2 -o /dev/null $f; done`.
