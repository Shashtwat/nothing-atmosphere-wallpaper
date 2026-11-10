#version 300 es
precision mediump float;
out vec4 FragColor;
// Nothing streams the colour per vertex; it is constant per blob, so a uniform gives the
// identical FragColor = vec4(ourColor, uAlpha).
uniform vec3 uColor;
uniform float uAlpha;
void main() {
    FragColor = vec4(uColor, uAlpha);
}
