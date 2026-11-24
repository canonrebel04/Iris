package net.irisshaders.iris.anr.fsr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class FsrShaders {
	private FsrShaders() {
	}

	static String read(String resource) {
		try (var stream = Objects.requireNonNull(FsrShaders.class.getResourceAsStream(resource), resource)) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
