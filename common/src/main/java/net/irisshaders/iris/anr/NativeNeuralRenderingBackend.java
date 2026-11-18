package net.irisshaders.iris.anr;

import net.irisshaders.iris.Iris;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Binds a user-supplied native library that implements the Iris neural rendering ABI
 * ({@code docs/ANR.md}, {@code docs/iris_anr_abi.h}). This class does not ship or link
 * any Apex Neural Rendering binary; the library is loaded only from {@code anrLibraryPath}.
 */
public final class NativeNeuralRenderingBackend implements NeuralRenderingBackend {
	private static final MemoryLayout TEXTURE_LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("api"),
		MemoryLayout.paddingLayout(4),
		ValueLayout.JAVA_LONG.withName("handle"),
		ValueLayout.JAVA_INT.withName("width"),
		ValueLayout.JAVA_INT.withName("height")
	).withName("IrisAnrTexture");

	private static final MemoryLayout INIT_LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("abi_version"),
		ValueLayout.JAVA_INT.withName("api")
	).withName("IrisAnrInit");

	private static final MemoryLayout FRAME_LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("flags"),
		MemoryLayout.paddingLayout(4),
		TEXTURE_LAYOUT.withName("color"),
		TEXTURE_LAYOUT.withName("depth"),
		TEXTURE_LAYOUT.withName("motion_vectors"),
		MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("view"),
		MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("projection"),
		MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("prev_view"),
		MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("prev_projection"),
		ValueLayout.JAVA_FLOAT.withName("jitter_x"),
		ValueLayout.JAVA_FLOAT.withName("jitter_y"),
		ValueLayout.JAVA_FLOAT.withName("exposure"),
		ValueLayout.JAVA_FLOAT.withName("frame_delta_seconds"),
		ValueLayout.JAVA_FLOAT.withName("sr_scale"),
		ValueLayout.JAVA_INT.withName("sr_quality")
	).withName("IrisAnrFrame");

	private static final MemoryLayout RESULT_LAYOUT = MemoryLayout.structLayout(
		ValueLayout.JAVA_INT.withName("status"),
		ValueLayout.JAVA_INT.withName("generated_frames"),
		ValueLayout.JAVA_INT.withName("output_width"),
		ValueLayout.JAVA_INT.withName("output_height")
	).withName("IrisAnrResult");

	private final Arena arena;
	private final MethodHandle abiVersion;
	private final MethodHandle capabilities;
	private final MethodHandle init;
	private final MethodHandle shutdown;
	private final MethodHandle evaluate;
	private final Set<NeuralCapability> caps;
	private boolean initialized;

	private NativeNeuralRenderingBackend(Arena arena, MethodHandle abiVersion, MethodHandle capabilities, MethodHandle init,
		MethodHandle shutdown, MethodHandle evaluate, Set<NeuralCapability> caps) {
		this.arena = arena;
		this.abiVersion = abiVersion;
		this.capabilities = capabilities;
		this.init = init;
		this.shutdown = shutdown;
		this.evaluate = evaluate;
		this.caps = caps;
	}

	public static NativeNeuralRenderingBackend open(Path library) throws Throwable {
		Arena arena = Arena.ofShared();
		try {
			SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
			Linker linker = Linker.nativeLinker();
			MethodHandle abiVersion = downcall(lookup, linker, "iris_anr_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
			MethodHandle capabilities = downcall(lookup, linker, "iris_anr_capabilities", FunctionDescriptor.of(ValueLayout.JAVA_INT));
			MethodHandle init = downcall(lookup, linker, "iris_anr_init",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MethodHandle shutdown = downcall(lookup, linker, "iris_anr_shutdown", FunctionDescriptor.ofVoid());
			MethodHandle evaluate = downcall(lookup, linker, "iris_anr_evaluate",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

			int version = (int) abiVersion.invokeExact();
			if (version != NeuralInitInfo.ABI_VERSION) {
				arena.close();
				throw new IllegalStateException("native library ABI " + version + " does not match Iris ABI " + NeuralInitInfo.ABI_VERSION);
			}

			int capBits = (int) capabilities.invokeExact();
			EnumSet<NeuralCapability> caps = EnumSet.noneOf(NeuralCapability.class);
			for (NeuralCapability cap : NeuralCapability.values()) {
				if ((capBits & cap.bit()) != 0) {
					caps.add(cap);
				}
			}

			return new NativeNeuralRenderingBackend(arena, abiVersion, capabilities, init, shutdown, evaluate, Set.copyOf(caps));
		} catch (Throwable t) {
			arena.close();
			throw t;
		}
	}

	private static MethodHandle downcall(SymbolLookup lookup, Linker linker, String name, FunctionDescriptor descriptor) {
		MemorySegment symbol = lookup.find(name)
			.orElseThrow(() -> new UnsatisfiedLinkError("native library is missing required symbol " + name));
		return linker.downcallHandle(symbol, descriptor);
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public Set<NeuralCapability> capabilities() {
		return caps;
	}

	@Override
	public void init(NeuralInitInfo info) {
		if (initialized) {
			return;
		}

		try (Arena call = Arena.ofConfined()) {
			MemorySegment init = call.allocate(INIT_LAYOUT);
			init.set(ValueLayout.JAVA_INT, 0, info.abiVersion());
			init.set(ValueLayout.JAVA_INT, 4, info.graphicsApi());
			int status = (int) this.init.invokeExact(init);
			if (status != 0) {
				throw new IllegalStateException("iris_anr_init returned " + status);
			}
			initialized = true;
		} catch (Error | RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException("failed to initialize native neural rendering backend", t);
		}
	}

	@Override
	public void shutdown() {
		if (!initialized) {
			arena.close();
			return;
		}

		try {
			shutdown.invokeExact();
		} catch (Throwable t) {
			Iris.logger.warn("iris_anr_shutdown failed", t);
		} finally {
			initialized = false;
			arena.close();
		}
	}

	@Override
	public NeuralFrameResult evaluate(NeuralFrameInputs inputs) {
		try (Arena call = Arena.ofConfined()) {
			MemorySegment frame = call.allocate(FRAME_LAYOUT);
			frame.set(ValueLayout.JAVA_INT, 0, inputs.featureFlags);
			writeTexture(frame, 8, inputs.color);
			writeTexture(frame, 32, inputs.depth);
			writeTexture(frame, 56, inputs.motionVectors);
			writeFloats(frame, 80, inputs.view);
			writeFloats(frame, 144, inputs.projection);
			writeFloats(frame, 208, inputs.previousView);
			writeFloats(frame, 272, inputs.previousProjection);
			frame.set(ValueLayout.JAVA_FLOAT, 336, inputs.jitterX);
			frame.set(ValueLayout.JAVA_FLOAT, 340, inputs.jitterY);
			frame.set(ValueLayout.JAVA_FLOAT, 344, inputs.exposure);
			frame.set(ValueLayout.JAVA_FLOAT, 348, inputs.frameDeltaSeconds);
			frame.set(ValueLayout.JAVA_FLOAT, 352, inputs.superResolutionScale);
			frame.set(ValueLayout.JAVA_INT, 356, inputs.superResolutionQuality);

			MemorySegment result = call.allocate(RESULT_LAYOUT);
			int status = (int) evaluate.invokeExact(frame, result);
			return new NeuralFrameResult(status, result.get(ValueLayout.JAVA_INT, 4), result.get(ValueLayout.JAVA_INT, 8),
				result.get(ValueLayout.JAVA_INT, 12));
		} catch (Error | RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException("iris_anr_evaluate failed", t);
		}
	}

	private static void writeTexture(MemorySegment frame, long offset, NeuralTextureHandle texture) {
		NeuralTextureHandle value = texture == null ? NeuralTextureHandle.empty() : texture;
		frame.set(ValueLayout.JAVA_INT, offset, value.api());
		frame.set(ValueLayout.JAVA_LONG, offset + 8, value.handle());
		frame.set(ValueLayout.JAVA_INT, offset + 16, value.width());
		frame.set(ValueLayout.JAVA_INT, offset + 20, value.height());
	}

	private static void writeFloats(MemorySegment frame, long offset, float[] values) {
		for (int i = 0; i < 16; i++) {
			frame.set(ValueLayout.JAVA_FLOAT, offset + (i * 4L), values[i]);
		}
	}
}
