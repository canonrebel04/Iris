/* Iris neural rendering ABI, version 1.
 *
 * A native library loaded via iris.properties anrLibraryPath must export the
 * functions below. This is a sketch of the contract Iris implements in
 * net.irisshaders.iris.anr.NativeNeuralRenderingBackend. It is not an Apex
 * Neural Rendering SDK header, and Iris does not ship any ANR library.
 *
 * Layout is natural C alignment on LP64 (Linux x86-64 / Windows x64).
 */
#ifndef IRIS_ANR_ABI_H
#define IRIS_ANR_ABI_H

#include <stdint.h>

#define IRIS_ANR_ABI_VERSION 1

enum IrisAnrApi {
	IRIS_ANR_API_NONE = 0,
	IRIS_ANR_API_OPENGL = 1,
	IRIS_ANR_API_VULKAN = 2
};

enum IrisAnrCapability {
	IRIS_ANR_CAP_FG = 1 << 0,
	IRIS_ANR_CAP_SR = 1 << 1,
	IRIS_ANR_CAP_RR = 1 << 2
};

enum IrisAnrSrQuality {
	IRIS_ANR_SR_PERFORMANCE = 0,
	IRIS_ANR_SR_BALANCED = 1,
	IRIS_ANR_SR_QUALITY = 2
};

typedef struct IrisAnrTexture {
	int32_t api;     /* IrisAnrApi */
	uint64_t handle; /* GLuint (OpenGL) or VkImage (Vulkan); 0 if not provided */
	int32_t width;
	int32_t height;
} IrisAnrTexture; /* size 24, align 8 */

typedef struct IrisAnrInit {
	int32_t abi_version; /* must be IRIS_ANR_ABI_VERSION */
	int32_t api;         /* current Iris graphics backend */
} IrisAnrInit;

typedef struct IrisAnrFrame {
	int32_t flags; /* IrisAnrCapability bits requested this frame */
	IrisAnrTexture color;
	IrisAnrTexture depth;
	IrisAnrTexture motion_vectors; /* handle = 0 if unavailable */
	float view[16];                /* column-major */
	float projection[16];
	float prev_view[16];
	float prev_projection[16];
	float jitter_x;
	float jitter_y;
	float exposure;
	float frame_delta_seconds;
	float sr_scale;
	int32_t sr_quality; /* IrisAnrSrQuality */
} IrisAnrFrame; /* size 360, align 8; flags is followed by 4 bytes of padding */

typedef struct IrisAnrResult {
	int32_t status; /* 0 = ok, 1 = skipped, negative = error */
	int32_t generated_frames;
	int32_t output_width;
	int32_t output_height;
} IrisAnrResult;

#ifdef __cplusplus
extern "C" {
#endif

int32_t iris_anr_abi_version(void);
int32_t iris_anr_capabilities(void);
int32_t iris_anr_init(const IrisAnrInit *init);
void iris_anr_shutdown(void);
int32_t iris_anr_evaluate(const IrisAnrFrame *frame, IrisAnrResult *out);

#ifdef __cplusplus
}
#endif

#endif /* IRIS_ANR_ABI_H */
