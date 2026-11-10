#version 300 es
// Nothing OS 5.0 noise pass, verbatim apart from the varying name.
precision highp float;
in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D uSampler;
uniform float uNoiseGrowth;

float random(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

void main() {
    vec2 uv = vTexCoord;
    float noise = random(uv);
    vec3 color = texture(uSampler, uv).rgb;
    FragColor = vec4(mix(color, vec3(noise), uNoiseGrowth), 1.0);
}
