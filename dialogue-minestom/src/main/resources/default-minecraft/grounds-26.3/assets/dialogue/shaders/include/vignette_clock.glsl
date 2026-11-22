const float VIGNETTE_PERIOD_TICKS = 24000.0;
const float VIGNETTE_TIMESTAMP_SCALE = 16777216.0;
const float VIGNETTE_ACTIVE_SENTINEL = 0.5;
const float VIGNETTE_DONE_SENTINEL = 1.0;
float vignetteTimestamp(vec4 state) {
    uvec3 bytes = uvec3(round(state.rgb * 255.0));
    uint encoded = (bytes.r << 16u) | (bytes.g << 8u) | bytes.b;
    return float(encoded) / VIGNETTE_TIMESTAMP_SCALE;
}
float vignetteProgress(vec4 state) {
    if (state.a == VIGNETTE_DONE_SENTINEL) return 1.0;
    if (state.a == 0.0) return 0.0;
    float elapsedTicks = (GameTime - vignetteTimestamp(state)) * VIGNETTE_PERIOD_TICKS;
    if (elapsedTicks < -VIGNETTE_PERIOD_TICKS * 0.5) elapsedTicks += VIGNETTE_PERIOD_TICKS;
    return clamp(elapsedTicks / DurationTicks, 0.0, 1.0);
}
