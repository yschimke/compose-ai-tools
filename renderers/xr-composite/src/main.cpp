// xr-composite — render a SpatialScene (scene.json + panel PNGs) to a composite PNG, headless.
// Spike: Filament OpenGL backend on llvmpipe under Xvfb. No GPU required.

// Third-party headers FIRST: Filament's utils/debug.h defines an `assert_invariant`
// macro that otherwise clobbers nlohmann::json's member function of the same name.
#include "json.hpp"
#include "spatial_scene.hpp"  // generated typed mirror of the SpatialScene wire contract
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#include <filament/Engine.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/View.h>
#include <filament/Camera.h>
#include <filament/Viewport.h>
#include <filament/SwapChain.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/Texture.h>
#include <filament/TextureSampler.h>
#include <filament/IndexBuffer.h>
#include <filament/VertexBuffer.h>
#include <filament/RenderableManager.h>
#include <filament/TransformManager.h>
#include <filament/ColorGrading.h>
#include <filament/ToneMapper.h>
#include <filament/Skybox.h>

#include <backend/PixelBufferDescriptor.h>

#include <utils/EntityManager.h>
#include <utils/Entity.h>

#include <math/mat4.h>
#include <math/vec3.h>
#include <math/vec4.h>
#include <math/quat.h>
#include <math/half.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

using namespace filament;
using namespace filament::math;
using json = nlohmann::json;

namespace {

// MSVC's <cmath> only defines M_PI when _USE_MATH_DEFINES is set before the
// include; a local constant keeps the source portable across compilers.
constexpr double kPi = 3.14159265358979323846;

struct Args {
  std::string sceneDir;
  std::string scenePath;
  std::string outPath = "composite.png";
  std::string materialsDir;
  uint32_t width = 1280;
  uint32_t height = 800;
  std::string environment;  // CLI override: a preset name, or "color:#RRGGBB"
};

std::vector<uint8_t> readFile(const std::string& path) {
  std::ifstream f(path, std::ios::binary);
  if (!f) { fprintf(stderr, "cannot open %s\n", path.c_str()); exit(2); }
  return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)),
                              std::istreambuf_iterator<char>());
}

float srgbToLinear(float c) {
  return (c <= 0.04045f) ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

float3 hexToLinear(const std::string& hex) {
  // #RRGGBB
  auto h = hex;
  if (!h.empty() && h[0] == '#') h = h.substr(1);
  if (h.size() < 6) return {0.06f, 0.06f, 0.08f};
  auto byte = [&](int i) { return std::stoi(h.substr(i, 2), nullptr, 16) / 255.0f; };
  return {srgbToLinear(byte(0)), srgbToLinear(byte(2)), srgbToLinear(byte(4))};
}

struct Vertex { float x, y, z, u, v; };

// GLSL-style smoothstep on a single scalar (Filament's math headers don't export one for floats).
float smoothstep01(float x) {
  x = std::clamp(x, 0.0f, 1.0f);
  return x * x * (3.0f - 2.0f * x);
}

// Direction (unnormalized) for a cubemap texel. `face` follows Filament's FaceOffsets order
// (+X,-X,+Y,-Y,+Z,-Z); (u,v) are in [-1,1] across the face. Matches the standard GL cubemap
// convention so the +Y face points straight up (dir.y == 1).
float3 cubeDir(int face, float u, float v) {
  switch (face) {
    case 0: return { 1.0f,  -v,   -u};  // +X
    case 1: return {-1.0f,  -v,    u};  // -X
    case 2: return {  u,  1.0f,    v};  // +Y
    case 3: return {  u, -1.0f,   -v};  // -Y
    case 4: return {  u,   -v,  1.0f};  // +Z
    default:return { -u,   -v, -1.0f};  // -Z
  }
}

// Build a vertical-gradient environment cubemap emulating an HDRI sky/horizon, and install it as the
// scene's skybox. `sky` is the colour straight up, `horizon` the colour at eye level; a soft glow
// band brightens the horizon to read as atmospheric haze. `floor` (when set, i.e. `hasFloor`) colours
// the lower hemisphere (dir.y < 0), turning the 2-stop gradient into a 3-stop room-like environment
// with a ceiling, wall, and floor; when unset the lower hemisphere mirrors the original 2-stop
// horizon→sky interpolation so the legacy look is byte-for-byte preserved. `glow` scales the horizon
// glow band. RGBA16F (half) texels, one face at a time via Texture::FaceOffsets. The texel buffer is
// heap-allocated and freed in the PixelBufferDescriptor callback — Filament reads it on the driver
// thread during flushAndWait, so it must outlive this call (same idiom as the panel textures below).
Skybox* buildGradientSkybox(Engine& engine, float3 sky, float3 horizon, float3 floor, bool hasFloor,
                            float glow) {
  constexpr uint32_t kFace = 128;
  constexpr uint32_t kTexelsPerFace = kFace * kFace;
  constexpr uint32_t kChannels = 4;
  const size_t faceBytes = (size_t)kTexelsPerFace * kChannels * sizeof(half);
  // 6 faces packed back-to-back; FaceOffsets(faceBytes/elem≈) indexes by element count below.
  auto* texels = new half[(size_t)kTexelsPerFace * kChannels * 6];

  for (int face = 0; face < 6; ++face) {
    half* dst = texels + (size_t)face * kTexelsPerFace * kChannels;
    for (uint32_t y = 0; y < kFace; ++y) {
      for (uint32_t x = 0; x < kFace; ++x) {
        // texel centre in [-1, 1]
        float u = ((x + 0.5f) / kFace) * 2.0f - 1.0f;
        float v = ((y + 0.5f) / kFace) * 2.0f - 1.0f;
        float3 dir = normalize(cubeDir(face, u, v));
        float3 c;
        if (hasFloor && dir.y < 0.0f) {
          // Lower hemisphere: interpolate horizon (at the wall) → floor (straight down).
          float t = smoothstep01(-dir.y);  // 0 at horizon, 1 straight down
          c = mix(horizon, floor, t);
        } else {
          // Upper hemisphere (and the whole sphere in legacy 2-stop mode): horizon → sky.
          float t = smoothstep01(dir.y * 0.5f + 0.5f);
          c = mix(horizon, sky, t);
        }
        // Soft horizon glow: a gentle band centred on dir.y == 0, fading above and below.
        float glowBand = std::exp(-(dir.y * dir.y) / (2.0f * 0.06f * 0.06f));
        c = c + horizon * (glowBand * glow);
        size_t i = ((size_t)y * kFace + x) * kChannels;
        dst[i + 0] = half(c.r);
        dst[i + 1] = half(c.g);
        dst[i + 2] = half(c.b);
        dst[i + 3] = half(1.0f);
      }
    }
  }

  Texture* cube = Texture::Builder()
      .width(kFace).height(kFace).levels(1)
      .format(Texture::InternalFormat::RGBA16F)
      .sampler(Texture::Sampler::SAMPLER_CUBEMAP)
      .build(engine);

  Texture::FaceOffsets offsets(faceBytes);  // per-face offset in bytes, +X,-X,+Y,-Y,+Z,-Z
  Texture::PixelBufferDescriptor pbd(
      texels, (size_t)kTexelsPerFace * kChannels * 6 * sizeof(half),
      Texture::Format::RGBA, Texture::Type::HALF,
      [](void* buf, size_t, void*) { delete[] (half*)buf; }, nullptr);
  cube->setImage(engine, 0, std::move(pbd), offsets);

  return Skybox::Builder().environment(cube).showSun(false).build(engine);
}

// A named gradient-skybox preset. `floor` is only used when `hasFloor` is true (a 3-stop, room-like
// environment); otherwise the lower hemisphere mirrors the upper one (the classic 2-stop look).
struct GradientPreset {
  const char* name;
  const char* sky;      // colour straight up (#RRGGBB)
  const char* horizon;  // colour at eye level (#RRGGBB)
  const char* floor;    // colour straight down (#RRGGBB); ignored unless hasFloor
  bool hasFloor;
  float glow;  // horizon glow-band strength
};

// Built-in backdrops. `warm-room` is the default: a softly-lit, muted warm passthrough room
// (warm-taupe ceiling, warm wall at the horizon with a gentle glow, deep warm-brown floor) tuned to
// echo real Android XR rooms while keeping the light panel surfaces popping. `studio-dark` preserves
// the original cold 2-stop gradient byte-for-byte (no floor, glow 0.35).
constexpr GradientPreset kPresets[] = {
    {"warm-room", "#332e27", "#5a4d40", "#1e1a16", true, 0.30f},
    {"studio-dark", "#05070d", "#1a1f2b", "", false, 0.35f},
};
constexpr const char* kDefaultPreset = "warm-room";

const GradientPreset* findPreset(const std::string& name) {
  for (const auto& p : kPresets)
    if (name == p.name) return &p;
  return nullptr;
}

}  // namespace

int main(int argc, char** argv) {
  Args args;
  for (int i = 1; i < argc; i++) {
    std::string a = argv[i];
    auto next = [&]() { return std::string(argv[++i]); };
    if (a == "--scene") args.scenePath = next();
    else if (a == "--out") args.outPath = next();
    else if (a == "--materials") args.materialsDir = next();
    else if (a == "--width") args.width = std::stoul(next());
    else if (a == "--height") args.height = std::stoul(next());
    else if (a == "--environment") args.environment = next();
  }
  if (args.scenePath.empty()) { fprintf(stderr, "usage: --scene scene.json --out out.png\n"); return 2; }
  // A bare filename (no '/') means the scene lives in the current directory; find_last_of
  // returns npos there, so default to "." rather than treating the filename as the directory.
  auto slash = args.scenePath.find_last_of('/');
  args.sceneDir = (slash == std::string::npos) ? "." : args.scenePath.substr(0, slash);
  if (args.materialsDir.empty()) args.materialsDir = ".";

  // ---- parse scene.json (typed via the generated SpatialScene mirror) ----
  xrcomposite::SpatialScene scene;
  {
    std::ifstream f(args.scenePath);
    json raw;
    f >> raw;
    scene = raw.get<xrcomposite::SpatialScene>();
  }

  const uint32_t W = args.width, H = args.height;

  // ---- engine / render targets ----
  Engine* engine = Engine::Builder().backend(backend::Backend::OPENGL).build();
  if (!engine) { fprintf(stderr, "engine create failed\n"); return 3; }
  SwapChain* swapChain = engine->createSwapChain(W, H);
  Renderer* renderer = engine->createRenderer();
  Scene* fscene = engine->createScene();
  View* view = engine->createView();
  utils::Entity camEntity = utils::EntityManager::get().create();
  Camera* camera = engine->createCamera(camEntity);

  view->setScene(fscene);
  view->setCamera(camera);
  view->setViewport({0, 0, W, H});

  // faithful colors: linear tonemap (no filmic curve), keep post-processing for sRGB output + MSAA
  LinearToneMapper toneMapper;
  ColorGrading* colorGrading = ColorGrading::Builder().toneMapper(&toneMapper).build(*engine);
  view->setColorGrading(colorGrading);
  view->setPostProcessingEnabled(true);
  {
    MultiSampleAntiAliasingOptions msaa;
    msaa.enabled = true;
    msaa.sampleCount = 4;
    view->setMultiSampleAntiAliasingOptions(msaa);
    view->setAntiAliasing(View::AntiAliasing::FXAA);
  }

  // ---- background ----
  // The backdrop is a swappable, room-like vertical-gradient environment cubemap (HDRI-style
  // ceiling/wall/floor) so the light Material panels pop. Selection precedence, most → least
  // specific:
  //   1. CLI `--environment <name>`     → a named preset (see kPresets)
  //      CLI `--environment color:#RRGGBB` → a flat-colour skybox
  //   2. scene.json `environment`:
  //        kind=="color"                 → flat colour (uses `color`)
  //        else                          → `preset` selects a named preset; explicit
  //                                        `sky`/`horizon`/`floor`/`glow` OVERRIDE its values
  //        (legacy scenes with kind=="gradient" + sky/horizon still render: a custom-gradient
  //         override on top of the default preset)
  //   3. Built-in default = `warm-room`.
  // `bg` doubles as the clear colour for the readback path: it mirrors the horizon in gradient mode
  // and the colour in colour mode, so any uncovered edge blends with the backdrop.
  bool wantColor = false;       // resolved as a flat-colour skybox?
  float3 colorBg = {0.06f, 0.06f, 0.08f};

  // Start from the default preset, then layer overrides on top.
  const GradientPreset* preset = findPreset(kDefaultPreset);
  float3 gradSky = hexToLinear(preset->sky);
  float3 gradHorizon = hexToLinear(preset->horizon);
  float3 gradFloor = hexToLinear(preset->floor);
  bool gradHasFloor = preset->hasFloor;
  float gradGlow = preset->glow;

  // Applies a named preset's params over the current gradient state.
  auto applyPreset = [&](const GradientPreset* p) {
    gradSky = hexToLinear(p->sky);
    gradHorizon = hexToLinear(p->horizon);
    gradFloor = hexToLinear(p->floor);
    gradHasFloor = p->hasFloor;
    gradGlow = p->glow;
  };

  // (2) scene.json environment.
  if (scene.environment) {
    const auto& env = *scene.environment;
    if (env.kind == "color") {
      wantColor = true;
      if (env.color) colorBg = hexToLinear(*env.color);
    } else {
      if (env.preset) {
        if (const GradientPreset* p = findPreset(*env.preset)) applyPreset(p);
        else fprintf(stderr, "unknown environment preset '%s'; using default\n",
                     env.preset->c_str());
      }
      // Explicit fields override the chosen preset (custom gradient).
      if (env.sky) gradSky = hexToLinear(*env.sky);
      if (env.horizon) gradHorizon = hexToLinear(*env.horizon);
      if (env.floor) {
        gradFloor = hexToLinear(*env.floor);
        gradHasFloor = true;
      }
      if (env.glow) gradGlow = static_cast<float>(*env.glow);
    }
  }

  // (1) CLI override — highest precedence, wins over scene.json.
  if (!args.environment.empty()) {
    const std::string& e = args.environment;
    if (e.rfind("color:", 0) == 0) {
      wantColor = true;
      colorBg = hexToLinear(e.substr(6));
    } else if (const GradientPreset* p = findPreset(e)) {
      wantColor = false;
      applyPreset(p);
    } else {
      fprintf(stderr, "unknown --environment '%s'; using scene/default backdrop\n", e.c_str());
    }
  }

  float3 bg;
  Skybox* skybox = nullptr;
  if (wantColor) {
    bg = colorBg;
    skybox = Skybox::Builder().color({bg.r, bg.g, bg.b, 1.0f}).build(*engine);
  } else {
    // Clear colour mirrors the horizon so any uncovered edge blends with the gradient.
    bg = gradHorizon;
    skybox = buildGradientSkybox(*engine, gradSky, gradHorizon, gradFloor, gradHasFloor, gradGlow);
    if (!skybox) {
      // Fall back to a flat horizon-coloured skybox if the cubemap couldn't be built.
      fprintf(stderr, "gradient skybox build failed; falling back to solid\n");
      skybox = Skybox::Builder().color({bg.r, bg.g, bg.b, 1.0f}).build(*engine);
    }
  }
  fscene->setSkybox(skybox);

  // ---- material ----
  auto unlitPkg = readFile(args.materialsDir + "/unlit_texture.filamat");
  Material* unlit = Material::Builder().package(unlitPkg.data(), unlitPkg.size()).build(*engine);

  // Soft contact shadow behind each panel — a separate transparent quad that feathers a rounded-rect
  // silhouette. Real Filament shadows are unstable / expensive on llvmpipe, so we composite a baked
  // soft shadow quad instead (deterministic, no shadow map, no GPU shadow pass).
  auto shadowPkg = readFile(args.materialsDir + "/panel_shadow.filamat");
  Material* shadowMat =
      Material::Builder().package(shadowPkg.data(), shadowPkg.size()).build(*engine);

  TextureSampler sampler(TextureSampler::MinFilter::LINEAR_MIPMAP_LINEAR,
                         TextureSampler::MagFilter::LINEAR);

  auto& tcm = engine->getTransformManager();

  // Build a textured/coloured quad renderable spanning [-hw,hw] x [-hh,hh] in the XY plane, UV0
  // running (0,0) bottom-left to (1,1) top-right. Heap-allocates the vertex/index buffers and frees
  // them in the descriptor callbacks (Filament reads them on the driver thread during flushAndWait).
  auto buildQuad = [&](float hw, float hh, MaterialInstance* mi) -> utils::Entity {
    auto* verts = new Vertex[4]{
        {-hw, -hh, 0, 0, 0},
        { hw, -hh, 0, 1, 0},
        { hw,  hh, 0, 1, 1},
        {-hw,  hh, 0, 0, 1},
    };
    auto* idx = new uint16_t[6]{0, 1, 2, 0, 2, 3};
    VertexBuffer* vb = VertexBuffer::Builder()
        .vertexCount(4).bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0, VertexBuffer::AttributeType::FLOAT3, 0, sizeof(Vertex))
        .attribute(VertexAttribute::UV0, 0, VertexBuffer::AttributeType::FLOAT2, offsetof(Vertex, u), sizeof(Vertex))
        .build(*engine);
    vb->setBufferAt(*engine, 0, VertexBuffer::BufferDescriptor(
        verts, sizeof(Vertex) * 4, [](void* p, size_t, void*) { delete[] (Vertex*)p; }));
    IndexBuffer* ib = IndexBuffer::Builder()
        .indexCount(6).bufferType(IndexBuffer::IndexType::USHORT).build(*engine);
    ib->setBuffer(*engine, IndexBuffer::BufferDescriptor(
        idx, sizeof(uint16_t) * 6, [](void* p, size_t, void*) { delete[] (uint16_t*)p; }));
    utils::Entity e = utils::EntityManager::get().create();
    RenderableManager::Builder(1)
        .boundingBox({{-hw, -hh, -1.0f}, {hw, hh, 1.0f}})
        .material(0, mi)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, vb, ib, 0, 6)
        .culling(false).castShadows(false).receiveShadows(false)
        .build(*engine, e);
    return e;
  };

  // ---- panels ----
  int placed = 0;
  for (const auto& panel : scene.panels) {
    const std::string& id = panel.id;
    std::string texRel = panel.texture.empty() ? (id + ".png") : panel.texture;
    std::string texPath = args.sceneDir + "/" + texRel;

    // Filament readPixels on this swapchain is top-origin and UV0 (0,0) maps to the quad's
    // bottom-left, so loading the PNG top-down (no stb flip) keeps panel content upright.
    stbi_set_flip_vertically_on_load(false);
    int tw, th, tn;
    unsigned char* pix = stbi_load(texPath.c_str(), &tw, &th, &tn, 4);
    if (!pix) { fprintf(stderr, "  panel %s: no texture %s (skipped)\n", id.c_str(), texPath.c_str()); continue; }

    size_t texBytes = (size_t)tw * th * 4;
    Texture* tex = Texture::Builder()
        .width(tw).height(th).levels(0xff)
        .format(Texture::InternalFormat::SRGB8_A8)
        .usage(Texture::Usage::SAMPLEABLE | Texture::Usage::UPLOADABLE |
               Texture::Usage::GEN_MIPMAPPABLE)
        .sampler(Texture::Sampler::SAMPLER_2D)
        .build(*engine);
    Texture::PixelBufferDescriptor pbd(
        pix, texBytes, Texture::Format::RGBA, Texture::Type::UBYTE,
        [](void* buf, size_t, void*) { stbi_image_free(buf); }, nullptr);
    tex->setImage(*engine, 0, std::move(pbd));
    tex->generateMipmaps(*engine);

    float wDp = static_cast<float>(panel.sizeDp.width);
    float hDp = static_cast<float>(panel.sizeDp.height);
    float hw = wDp * 0.5f, hh = hDp * 0.5f;

    // Fidelity params evaluated in an aspect-corrected "rect space" where the panel spans
    // [-halfSize, halfSize] and rounded corners stay circular regardless of aspect (see the .mat).
    // For w>=h: halfSize=(aspect,1); else (1,1/aspect). Radius/rim/softness are fractions of the
    // shorter half-extent (always 1.0), so they read consistently across panel shapes.
    float aspect = (hDp > 0.0f) ? (wDp / hDp) : 1.0f;
    float2 halfSize = (wDp >= hDp) ? float2{aspect, 1.0f} : float2{1.0f, 1.0f / aspect};
    // ~24-32dp corner radius on a typical ~320dp-tall panel ≈ 0.16-0.20 of the half-extent.
    const float kCornerRadius = 0.18f;
    const float kRimWidth = 0.06f;     // rim band width inside the edge
    const float kRimStrength = 0.10f;  // subtle additive highlight
    const float kEdgeSoftness = 0.012f;  // AA feather of the rounded edge

    MaterialInstance* mi = unlit->createInstance();
    mi->setParameter("albedo", tex, sampler);
    mi->setParameter("halfSize", halfSize);
    mi->setParameter("cornerRadius", kCornerRadius);
    mi->setParameter("rimWidth", kRimWidth);
    mi->setParameter("rimStrength", kRimStrength);
    mi->setParameter("edgeSoftness", kEdgeSoftness);

    // transform: translate * rotate(quat)
    const auto& T = panel.poseInRoot.translation;
    const auto& R = panel.poseInRoot.rotation;
    float3 t = {static_cast<float>(T.x), static_cast<float>(T.y), static_cast<float>(T.z)};
    quatf q = {static_cast<float>(R.w), static_cast<float>(R.x), static_cast<float>(R.y),
               static_cast<float>(R.z)};
    mat4f rot(q);

    // ---- soft drop/contact shadow behind+below the panel ----
    // Mirror the panel's rect-space metrics so the shadow silhouette matches the rounded panel, then
    // map the aspect-corrected units back to dp via the per-axis dp-per-unit scale. The shadow quad
    // is inflated by `blur` so its feathered penumbra has room; it sits slightly behind the panel
    // (toward -Z in panel-local space) and is offset down a touch for a grounded, floating look.
    {
      float dpPerUnitX = hw / halfSize.x;   // == hh / halfSize.y
      float blurUnits = 0.22f;              // penumbra width in rect-space units
      float2 outerHalf = halfSize + float2{blurUnits, blurUnits};
      float shw = outerHalf.x * dpPerUnitX;
      float shh = outerHalf.y * dpPerUnitX;

      MaterialInstance* smi = shadowMat->createInstance();
      smi->setParameter("halfSize", halfSize);
      smi->setParameter("cornerRadius", kCornerRadius);
      smi->setParameter("blur", blurUnits);
      // Soft near-black shadow; peak alpha kept subtle so it reads as a contact shadow, not a slab.
      smi->setParameter("color", float4{0.0f, 0.0f, 0.0f, 0.42f});

      utils::Entity se = buildQuad(shw, shh, smi);
      // Offset: down by ~6% of panel height and back behind the panel so it never z-fights the quad.
      float3 shadowOffset = {0.0f, -0.06f * hDp, -2.0f};
      mat4f shadowModel = mat4f::translation(t) * rot * mat4f::translation(shadowOffset);
      tcm.setTransform(tcm.getInstance(se), shadowModel);
      fscene->addEntity(se);
    }

    utils::Entity re = buildQuad(hw, hh, mi);
    mat4f model = mat4f::translation(t) * rot;
    tcm.setTransform(tcm.getInstance(re), model);

    fscene->addEntity(re);
    placed++;
    fprintf(stderr, "  panel %s: pos(%.0f,%.0f,%.0f) size %.0fx%.0f tex %dx%d\n",
            id.c_str(), t.x, t.y, t.z, wDp, hDp, tw, th);
  }
  fprintf(stderr, "placed %d panel(s)\n", placed);

  // ---- camera (orbit) ----
  const auto& cam = scene.camera;
  float3 target = {static_cast<float>(cam.target.x), static_cast<float>(cam.target.y),
                   static_cast<float>(cam.target.z)};
  double distance = cam.distance;
  double yaw = cam.yawDeg * kPi / 180.0;
  double pitch = cam.pitchDeg * kPi / 180.0;
  float3 dir = {(float)(std::cos(pitch) * std::sin(yaw)), (float)std::sin(pitch),
                (float)(std::cos(pitch) * std::cos(yaw))};
  float3 eye = target + dir * (float)distance;
  fprintf(stderr, "camera eye(%.0f,%.0f,%.0f) -> target(%.0f,%.0f,%.0f) dist %.0f\n",
          eye.x, eye.y, eye.z, target.x, target.y, target.z, distance);
  camera->lookAt(eye, target, {0, 1, 0});
  double aspect = (double)W / (double)H;
  camera->setProjection(45.0, aspect, 1.0, distance * 10.0 + 10000.0, Camera::Fov::VERTICAL);

  // ---- render + readback ----
  Renderer::ClearOptions co;
  co.clear = true;
  co.clearColor = {bg.r, bg.g, bg.b, 1.0f};
  renderer->setClearOptions(co);

  std::vector<uint8_t> pixels(W * H * 4);
  struct Capture { uint8_t* dst; size_t size; bool done; } capture{pixels.data(), pixels.size(), false};

  if (renderer->beginFrame(swapChain)) {
    renderer->render(view);
    backend::PixelBufferDescriptor pb(
        pixels.data(), pixels.size(),
        backend::PixelDataFormat::RGBA, backend::PixelDataType::UBYTE,
        [](void*, size_t, void* user) { ((Capture*)user)->done = true; }, &capture);
    renderer->readPixels(0, 0, W, H, std::move(pb));
    renderer->endFrame();
  } else {
    fprintf(stderr, "beginFrame returned false\n");
  }
  engine->flushAndWait();

  // Filament's readPixels here returns top-origin rows, matching PNG's top-left origin — no flip.
  if (!stbi_write_png(args.outPath.c_str(), W, H, 4, pixels.data(), W * 4)) {
    fprintf(stderr, "png write failed\n");
    return 4;
  }
  fprintf(stderr, "wrote %s (%ux%u)\n", args.outPath.c_str(), W, H);

  // Spike: skip explicit Filament teardown (asserts on destroy order); the process exits here.
  fflush(stderr);
  // std::_Exit terminates without running destructors/atexit (portable equivalent
  // of POSIX _exit); avoids Filament's teardown-order asserts on all platforms.
  std::_Exit(0);
}
