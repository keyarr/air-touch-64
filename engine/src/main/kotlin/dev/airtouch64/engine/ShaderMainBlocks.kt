package dev.airtouch64.engine

internal object ShaderMainBlocks {
    val byName = mapOf(
        "comic.fs" to """void main()
{
    vec4 col = texture2D(tt_uniform_maintexture, mainTexCoord);
    vec4 straight = vec4(col.a > 0.0 ? col.rgb / col.a : vec3(0.0), col.a);
    SHIFTCOMPS(straight)
    const mat3 rgbToYiq = mat3(
        0.299, 0.596, 0.212,
        0.587, -0.275, -0.523,
        0.114, -0.321, 0.311
    );
    const mat3 yiqToRgb = mat3(
        1.0, 1.0, 1.0,
        0.956, -0.272, -1.10,
        0.621, -0.647, 1.70
    );
    vec3 yiq = rgbToYiq * straight.rgb;
    float levels = max(threshold - 1.0, 1.0);
    yiq.x = floor(yiq.x * levels + 0.5) / levels;
    vec3 rgb = yiqToRgb * yiq;

    vec4 delta = abs(col - texture2D(tt_uniform_maintexture, mainTexCoord + vec2(-tt_uniform_pixelsize.x, 0.0)))
        + abs(col - texture2D(tt_uniform_maintexture, mainTexCoord + vec2(0.0, -tt_uniform_pixelsize.y)))
        + abs(col - texture2D(tt_uniform_maintexture, mainTexCoord + vec2(tt_uniform_pixelsize.x, 0.0)))
        + abs(col - texture2D(tt_uniform_maintexture, mainTexCoord + vec2(0.0, tt_uniform_pixelsize.y)));
    float edge = min(edgeStrength * (delta.r + delta.g + delta.b) / 12.0, 1.0);
    float edgeStart = 1.0 - edgeLevel;
    edge = edgeStart >= 1.0 ? 0.0 : clamp((edge - edgeStart) / (1.0 - edgeStart), 0.0, 1.0);
    rgb = mix(rgb, vec3(0.0), edge);
    vec4 dst = vec4(clamp(rgb, 0.0, 1.0) * col.a, col.a);
    SHIFTCOMPS(dst)
    gl_FragColor = dst;
}""",
        "posterize.fs" to """void main()
{
    vec4 dst = texture2D(tt_uniform_maintexture, mainTexCoord);
    vec3 rgb = dst.a > 0.0 ? dst.rgb / dst.a : vec3(0.0);
    float levels = max(numColors - 1.0, 1.0);
    rgb = floor(rgb * levels + 0.5) / levels;
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0) * dst.a, dst.a);
}""",
        "replacecolor.fs" to """void main()
{
    vec4 src = texture2D(tt_uniform_maintexture, mainTexCoord);
    SHIFTCOMPS(src)

    // BitmapData textures are premultiplied; compare straight colors instead.
    vec3 srcRGB = src.a > 0.0 ? src.rgb / src.a : vec3(0.0);
    vec3 replaceRGB = replaceColor.a > 0.0 ? replaceColor.rgb / replaceColor.a : vec3(0.0);

    vec3 dotVector = vec3(0.299, 0.587, 0.114);

    const mat3 YIQMatrix = mat3(
        0.299, 0.596, 0.212,
        0.587, -0.275, -0.523,
        0.114, -0.321, 0.311);

    const vec3 YIQVx = vec3(0.299, -0.587, -0.114);
    const vec3 YIQVy = vec3(0.596, -0.275, -0.321);
    const vec3 YIQVz = vec3(0.212, -0.523, 0.311);

    const vec3 inverseYIQVx = vec3(1.0, 0.956, 0.621);
    const vec3 inverseYIQVy = vec3(1.0, -0.272, -0.647);
    const vec3 inverseYIQVz = vec3(1.0, -1.10, 1.70);

    float dist = distance(YIQMatrix * sampleColor.rgb, YIQMatrix * srcRGB);
    float tres = threshold * threshold;
    float edge = tres * 0.75;
    float factor = tres > 0.0
        ? (dist <= edge ? 1.0 : max((tres - dist) / (tres - edge), 0.0))
        : step(dist, 0.0);

    // Transparent pixels must not match the sampled color.
    factor *= step(0.001, src.a);

    float replaceY = clamp(dot(dotVector, srcRGB) + dot(dotVector, replaceRGB) - dot(dotVector, sampleColor.rgb), 0.0, 1.0);
    vec3 replaceIQ = vec3(replaceY, dot(YIQVy, replaceRGB), dot(YIQVz, replaceRGB));
    vec3 outRGB = clamp(vec3(dot(inverseYIQVx, replaceIQ), dot(inverseYIQVy, replaceIQ), dot(inverseYIQVz, replaceIQ)), 0.0, 1.0);

    float outAlpha = src.a * replaceColor.a;
    vec4 dst = mix(src, vec4(outRGB * outAlpha, outAlpha), factor * intensity);

    SHIFTCOMPS(dst)
    gl_FragColor = dst;
}""",
        "threshold.fs" to """void main()
{
    vec4 src = texture2D(tt_uniform_maintexture, mainTexCoord);
    SHIFTCOMPS(src)
    vec3 rgb = src.a > 0.0 ? src.rgb / src.a : vec3(0.0);
    float lum = dot(rgb, vec3(0.299, 0.587, 0.114));
    float amount = smoothness <= 0.0
        ? step(threshold, lum)
        : smoothstep(threshold - smoothness * 0.5, threshold + smoothness * 0.5, lum);
    vec4 dst = mix(color1, color2, amount) * src.a;
    SHIFTCOMPS(dst)
    gl_FragColor = dst;
}"""
    )
}
