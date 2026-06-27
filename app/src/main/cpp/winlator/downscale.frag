#version 450

// =============================================================================
//  High-quality spatial downscale for supersampling (render res > display res).
//
//  Separable Lanczos-2 evaluated as a single 2D pass. The kernel widens by the
//  per-axis downscale ratio (srcSize/dstSize) so it acts as a proper low-pass
//  area filter for arbitrary ratios (1.25x / 1.5x / 2x). Quality over speed.
//
//  Fixed tap radius R=4 source texels per axis covers ratios up to 2x
//  (Lanczos-2 half-width = 2 * ratio <= 4). Taps beyond the kernel support
//  contribute zero weight, so smaller ratios cost the same loop but stay exact.
//
//  Reuses upscale.vert (fragTexCoord in [0,1] across the destination rect).
// =============================================================================

layout(binding = 0) uniform sampler2D src;

layout(push_constant) uniform PC {
    vec4 ndc;       // destination rect (consumed by upscale.vert, offset 0)
    vec2 srcSize;   // render (source) resolution in pixels
    vec2 dstSize;   // display (output) resolution in pixels
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265359;

float lanczos2(float x) {
    x = abs(x);
    if (x < 1e-5) return 1.0;
    if (x >= 2.0) return 0.0;
    float px = PI * x;
    return (2.0 * sin(px) * sin(px * 0.5)) / (px * px);
}

void main() {
    // Per-axis kernel stretch: >1 when downscaling (low-pass), clamped to >=1.
    vec2 scale = max(pc.srcSize / pc.dstSize, vec2(1.0));
    vec2 invScale = vec2(1.0) / scale;

    // Output-pixel centre projected into source-texel coordinates.
    vec2 c = fragTexCoord * pc.srcSize;
    ivec2 base = ivec2(floor(c));
    ivec2 sz = textureSize(src, 0);

    const int R = 4; // covers ratios up to 2x (half-width 2*scale <= 4)
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int dy = -R; dy <= R; ++dy) {
        for (int dx = -R; dx <= R; ++dx) {
            ivec2 i = base + ivec2(dx, dy);
            vec2 t = (vec2(i) + vec2(0.5) - c) * invScale;
            float w = lanczos2(t.x) * lanczos2(t.y);
            if (w == 0.0) continue;
            ivec2 ii = clamp(i, ivec2(0), sz - ivec2(1));
            acc += texelFetch(src, ii, 0).rgb * w;
            wsum += w;
        }
    }
    outColor = vec4(acc / max(wsum, 1e-5), 1.0);
}
