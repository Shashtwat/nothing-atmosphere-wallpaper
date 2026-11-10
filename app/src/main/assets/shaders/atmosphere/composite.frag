#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 outColor;

uniform sampler2D s_TextureMap;
uniform vec3  uBgColor;
uniform float uMixBlend;
// (1, 1) when the photo is screen-shaped (stretched, as Nothing does); otherwise a cover crop.
uniform vec2  uCoverScale;

// Glass: Nothing OS 5.0's glass shader. 0 = off.
uniform float uGlass;
uniform float size;
uniform float xFactorA;
uniform float xFactorB;
uniform float xFactorC;

const float ratio = 0.95;
const float xOffset = 0.063;
const float yOffset = 0.01;

float xStripeEffect(float partX, float x) {
    float s = xFactorA * partX * partX - xFactorB * partX + xFactorC;
    return x + s;
}

float yStripeEffect(float partX, float y) {
    float f = 0.05 * partX * partX - 0.03 * partX + 0.005;
    return y + f;
}

float brightnessEffect(float partX) {
    return 0.3 * partX * partX - 0.2 * partX;
}

vec3 adjustSaturation(vec3 c) {
    return mix(vec3(0.2), c, 0.92);
}

// uv here is image space: (0, 0) is the photo's top-left, matching Nothing's flipped v and the
// Bitmap upload (texture row 0 = image row 0), so it samples the texture directly.
vec3 glass(vec2 uv) {
    float partHeight = 1.0 / size;
    float partX = mod(uv.x, partHeight);
    vec2 n;
    if (partX < partHeight * ratio) {
        n.x = xStripeEffect(partX * size, uv.x);
        n.y = yStripeEffect(partX * size, uv.y);
    } else {
        n.x = uv.x + xOffset;
        n.y = uv.y + yOffset;
    }
    if (n.x > 1.0) n.x = 2.0 - n.x;
    else if (n.x < 0.0) n.x = 1.0 + n.x;
    if (n.y > 1.0) n.y = 2.0 - n.y;
    // Gradients of the undistorted uv: the rib discontinuities must not push mip selection up
    // and draw a blurred seam at every rib edge. Same texel footprint as a plain texture().
    vec4 tex = textureGrad(s_TextureMap, clamp(n, 0.0, 1.0), dFdx(uv), dFdy(uv));
    vec3 rgb = tex.rgb;
    if (partX < partHeight * ratio) {
        rgb = adjustSaturation(rgb);
        rgb += brightnessEffect(partX * size);
    }
    // Nothing renders the glass photo to an 8-bit image before the morph composites it.
    return clamp(rgb, 0.0, 1.0);
}

void main() {
    vec2 uv = (vTexCoord - 0.5) * uCoverScale + 0.5;
    // vTexCoord has v = 0 at the bottom; the photo's row 0 is its top.
    vec2 imageUv = vec2(uv.x, 1.0 - uv.y);
    vec3 sourceColor = uGlass > 0.5 ? glass(imageUv) : texture(s_TextureMap, imageUv).rgb;
    outColor = vec4(mix(sourceColor, uBgColor, uMixBlend), 1.0);
}
