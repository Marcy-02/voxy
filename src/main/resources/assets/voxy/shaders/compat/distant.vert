#version 460 core

//Distant contraption/track mesh: fully self-contained vertex path, no vanilla vertex formats or
//RenderTypes involved anywhere in the chain.

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec2 aLightUv;
layout(location = 3) in float aShade;
layout(location = 4) in uint aFace;
//Tint (biome grass/foliage colour etc.), white for untinted geometry
layout(location = 5) in vec4 aColor;

//Full transform: (pipeline MVP or vanilla proj*view) * model
layout(location = 0) uniform mat4 uTransform;
#ifdef UNIFORM_LIGHT
//Moving meshes bake without light; one light level per draw
layout(location = 4) uniform vec2 uLightUv;
//Baked faces are assembly-local; a rotated carriage/bogey needs them re-aimed or shader packs
//light (and specular-flare) the wrong side. Quarter turns about +Y, nearest-90 of the model yaw.
layout(location = 5) uniform uint uFaceRotY;
//Full continuous rotation matrix for arbitrary curve orientations
layout(location = 6) uniform mat3 uNormalMat;
//+90 deg about +Y: NORTH->WEST, SOUTH->EAST, WEST->SOUTH, EAST->NORTH (indexed by face-2)
const uint FACE_ROT_Y[4] = uint[4](4u, 5u, 3u, 2u);
#endif

layout(location = 0) out vec2 fUv;
layout(location = 1) out vec2 fLightUv;
layout(location = 2) out float fShade;
layout(location = 3) flat out uint fFace;
layout(location = 4) out vec4 fColor;
layout(location = 5) out vec3 fNormal;

const vec3 CUBE_NORMALS[6] = vec3[6](
    vec3(0.0, -1.0, 0.0),
    vec3(0.0, 1.0, 0.0),
    vec3(0.0, 0.0, -1.0),
    vec3(0.0, 0.0, 1.0),
    vec3(-1.0, 0.0, 0.0),
    vec3(1.0, 0.0, 0.0)
);

void main() {
    gl_Position = uTransform * vec4(aPos, 1.0);
    fUv = aUv;
    fColor = aColor;
    vec3 baseNorm = aFace < 6u ? CUBE_NORMALS[aFace] : vec3(0.0, 1.0, 0.0);
    #ifdef UNIFORM_LIGHT
    // Preserve baked emissive block light (e.g. lanterns/blaze burners on carriages)
    fLightUv = vec2(max(uLightUv.x, aLightUv.x), uLightUv.y);
    uint face = aFace;
    for (uint i = 0u; i < uFaceRotY; i++) {
        if (face >= 2u) {
            face = FACE_ROT_Y[face - 2u];
        }
    }
    fFace = face;
    vec3 norm = normalize(uNormalMat * baseNorm);
    fNormal = norm;
    // Continuous directional vanilla shade across curves:
    // Top: 1.0, Bottom: 0.5, North/South: 0.8, East/West: 0.6
    float shade = clamp(0.75 + 0.25 * norm.y + 0.05 * abs(norm.z) - 0.15 * abs(norm.x), 0.5, 1.0);
    if (aLightUv.x > 0.1) {
        shade = 1.0;
    }
    fShade = shade;
    #else
    fLightUv = aLightUv;
    fShade = aShade;
    fFace = aFace;
    fNormal = baseNorm;
    #endif
}
