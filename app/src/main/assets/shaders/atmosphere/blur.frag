#version 300 es
// Nothing OS 5.0 (libblurwallpaper.so) blur, verbatim apart from the varying name.
precision mediump float;
out vec4 FragColor;
in vec2 vTexCoord;

uniform sampler2D screenTexture;
uniform float uKernel[124];
uniform int uBlurRadius;
uniform vec2 uBlurOffset;

vec2 clampCoordinate(vec2 c) {
    return vec2(clamp(c.x, 0.0, 1.0), clamp(c.y, 0.0, 1.0));
}

void main() {
    vec4 sourceColor = texture(screenTexture, vTexCoord);
    if (uBlurRadius <= 1) {
        FragColor = sourceColor;
        return;
    }
    float weight = uKernel[0];
    vec3 finalColor = sourceColor.rgb * weight;
    for (int i = 1; i < uBlurRadius; i++) {
        weight = uKernel[i];
        finalColor += texture(screenTexture, clampCoordinate(vTexCoord - uBlurOffset * float(i))).rgb * weight;
        finalColor += texture(screenTexture, clampCoordinate(vTexCoord + uBlurOffset * float(i))).rgb * weight;
    }
    FragColor = vec4(finalColor, sourceColor.a);
}
