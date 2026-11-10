#version 300 es
precision mediump float;
layout (location = 0) in vec2 aPos;
uniform mat4 uMVPMatrix;
void main() {
    gl_Position = uMVPMatrix * vec4(aPos, 0.0, 1.0);
}
