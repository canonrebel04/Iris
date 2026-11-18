package net.irisshaders.iris.anr;

import java.util.Locale;

public enum NeuralSuperResolutionQuality {
	PERFORMANCE(0),
	BALANCED(1),
	QUALITY(2);

	private final int id;

	NeuralSuperResolutionQuality(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	public static NeuralSuperResolutionQuality fromConfig(String value) {
		if (value == null || value.isEmpty()) {
			return BALANCED;
		}

		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "performance", "perf", "0" -> PERFORMANCE;
			case "quality", "2" -> QUALITY;
			default -> BALANCED;
		};
	}
}
