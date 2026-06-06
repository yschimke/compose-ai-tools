// xr-composite — render a SpatialScene (scene.json + panel PNGs) to a composite PNG, headless.
// Spike: Filament OpenGL backend on llvmpipe under Xvfb. No GPU required.
//
// Two modes:
//   * one-shot (default): --scene scene.json --out composite.png → render once, exit.
//   * server (--serve): a long-lived JSON-RPC peer over stdio (LSP-style Content-Length framing,
//     the same framing the daemon's subprocess backend speaks). Holds one Filament Engine/Scene
//     across frames so panels can be updated per-frame (`xr/updatePanels`) and rendered frames
//     streamed back out (`streamFrame`). See docs/design/xr-spatial/RENDERER_SERVICE.md.

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
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
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
  bool serve = false;       // long-lived JSON-RPC server over stdio
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

// Standard base64 (RFC 4648) — the wire encoding for `streamFrame` PNG payloads. No padding tricks,
// no line wrapping; small and dependency-free.
std::string base64Encode(const uint8_t* data, size_t len) {
  static const char* kT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string out;
  out.reserve(((len + 2) / 3) * 4);
  size_t i = 0;
  for (; i + 2 < len; i += 3) {
    uint32_t n = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
    out.push_back(kT[(n >> 18) & 63]);
    out.push_back(kT[(n >> 12) & 63]);
    out.push_back(kT[(n >> 6) & 63]);
    out.push_back(kT[n & 63]);
  }
  if (i < len) {
    uint32_t n = data[i] << 16;
    bool two = (i + 1 < len);
    if (two) n |= data[i + 1] << 8;
    out.push_back(kT[(n >> 18) & 63]);
    out.push_back(kT[(n >> 12) & 63]);
    out.push_back(two ? kT[(n >> 6) & 63] : '=');
    out.push_back('=');
  }
  return out;
}

// Encode an RGBA buffer to PNG bytes in memory (stb writes via a callback rather than to a file).
std::vector<uint8_t> encodePng(const std::vector<uint8_t>& rgba, uint32_t w, uint32_t h) {
  std::vector<uint8_t> bytes;
  stbi_write_png_to_func(
      [](void* ctx, void* data, int size) {
        auto* v = static_cast<std::vector<uint8_t>*>(ctx);
        v->insert(v->end(), (uint8_t*)data, (uint8_t*)data + size);
      },
      &bytes, (int)w, (int)h, 4, rgba.data(), (int)w * 4);
  return bytes;
}

// Shared GL/Filament context fanned across sessions: one Engine + Renderer + compiled materials for
// the whole process. Each Compositor (session) owns only its own SwapChain/Scene/View/panels, so the
// server can multiplex many sessions over a single engine instead of one child process per session.
struct SharedGl {
  Engine* engine = nullptr;
  Renderer* renderer = nullptr;
  Material* unlit = nullptr;
  Material* shadow = nullptr;

  bool create(const std::string& materialsDir) {
    engine = Engine::Builder().backend(backend::Backend::OPENGL).build();
    if (!engine) { fprintf(stderr, "engine create failed\n"); return false; }
    renderer = engine->createRenderer();
    const std::string dir = materialsDir.empty() ? "." : materialsDir;
    auto unlitPkg = readFile(dir + "/unlit_texture.filamat");
    unlit = Material::Builder().package(unlitPkg.data(), unlitPkg.size()).build(*engine);
    // Soft contact shadow behind each panel — a separate transparent quad that feathers a
    // rounded-rect silhouette. Real Filament shadows are unstable / expensive on llvmpipe, so we
    // composite a baked soft shadow quad instead (deterministic, no shadow map, no GPU shadow pass).
    auto shadowPkg = readFile(dir + "/panel_shadow.filamat");
    shadow = Material::Builder().package(shadowPkg.data(), shadowPkg.size()).build(*engine);
    return true;
  }
};

// Holds one session's render state. `setScene` (re)builds the whole scene, `applyPanelUpdates`
// mutates panel poses/textures in place and rebuilds just the panels, and `renderPixels` reads back
// one frame. The Engine/Renderer/materials are shared (see [SharedGl]); each instance owns only its
// own SwapChain/Scene/View/Camera/panels so the server can hold many concurrently.
class Compositor {
 public:
  bool init(SharedGl& gl, uint32_t w, uint32_t h);
  // Replace the whole scene (background + panels + camera). `envOverride` is the CLI `--environment`
  // value (empty = none).
  void setScene(const xrcomposite::SpatialScene& scene, const std::string& sceneDir,
                const std::string& envOverride);
  // Per-frame panel mutation: each entry is `{id, texture?, poseInRoot?, sizeDp?}`. Unknown ids are
  // logged and skipped. Rebuilds the panel renderables (background/camera untouched).
  void applyPanelUpdates(const json& panels);
  std::vector<uint8_t> renderPixels();
  bool writePng(const std::string& path);
  // Destroy this session's per-session Filament objects (panels, skybox, view, scene, camera,
  // swapchain) via the shared engine; the shared Engine/Renderer/materials are left intact.
  void teardown();
  uint32_t width() const { return W_; }
  uint32_t height() const { return H_; }

 private:
  void resolveBackground(const std::string& envOverride);
  void rebuildPanels();
  void setupCamera();
  void clearPanels();
  utils::Entity buildQuad(float hw, float hh, MaterialInstance* mi);
  Texture* loadTexture(const std::string& relTexture, const std::string& fallbackId);

  Engine* engine_ = nullptr;
  SwapChain* swapChain_ = nullptr;
  Renderer* renderer_ = nullptr;
  Scene* scene_ = nullptr;
  View* view_ = nullptr;
  Camera* camera_ = nullptr;
  utils::Entity camEntity_;
  Material* unlit_ = nullptr;
  Material* shadowMat_ = nullptr;
  Skybox* skybox_ = nullptr;
  ColorGrading* colorGrading_ = nullptr;
  uint32_t W_ = 0, H_ = 0;
  std::string sceneDir_ = ".";
  float3 bg_ = {0.06f, 0.06f, 0.08f};

  xrcomposite::SpatialScene model_;  // current logical scene

  // Per-rebuild Filament resources, torn down on the next rebuild.
  std::vector<utils::Entity> panelEntities_;
  std::vector<VertexBuffer*> vbs_;
  std::vector<IndexBuffer*> ibs_;
  std::vector<MaterialInstance*> mis_;
  // Panel textures cached by resolved path so per-frame re-renders don't re-decode unchanged images.
  std::map<std::string, Texture*> texCache_;
};

bool Compositor::init(SharedGl& gl, uint32_t w, uint32_t h) {
  W_ = w;
  H_ = h;
  // Engine / Renderer / materials are shared across sessions; this instance owns only the
  // per-session SwapChain / Scene / View / Camera below.
  engine_ = gl.engine;
  renderer_ = gl.renderer;
  unlit_ = gl.unlit;
  shadowMat_ = gl.shadow;

  swapChain_ = engine_->createSwapChain(W_, H_);
  scene_ = engine_->createScene();
  view_ = engine_->createView();
  camEntity_ = utils::EntityManager::get().create();
  camera_ = engine_->createCamera(camEntity_);

  view_->setScene(scene_);
  view_->setCamera(camera_);
  view_->setViewport({0, 0, W_, H_});

  // faithful colors: linear tonemap (no filmic curve), keep post-processing for sRGB output + MSAA
  static LinearToneMapper toneMapper;
  colorGrading_ = ColorGrading::Builder().toneMapper(&toneMapper).build(*engine_);
  view_->setColorGrading(colorGrading_);
  view_->setPostProcessingEnabled(true);
  {
    MultiSampleAntiAliasingOptions msaa;
    msaa.enabled = true;
    msaa.sampleCount = 4;
    view_->setMultiSampleAntiAliasingOptions(msaa);
    view_->setAntiAliasing(View::AntiAliasing::FXAA);
  }
  return true;
}

void Compositor::teardown() {
  // No frame is in flight (renderPixels flushAndWait()s after each render), but flush defensively so
  // the driver isn't reading these objects when we destroy them. Engine/Renderer/materials are
  // shared — left for the next session / process exit.
  engine_->flushAndWait();
  clearPanels();
  // Panel textures are cached per resolved path for the session's lifetime; free them too (they live
  // in the shared engine and would otherwise leak after this session is erased).
  for (auto& [path, tex] : texCache_) engine_->destroy(tex);
  texCache_.clear();
  if (skybox_) {
    scene_->setSkybox(nullptr);
    engine_->destroy(skybox_);
    skybox_ = nullptr;
  }
  engine_->destroy(view_);
  engine_->destroy(scene_);
  engine_->destroyCameraComponent(camEntity_);
  utils::EntityManager::get().destroy(camEntity_);
  engine_->destroy(swapChain_);
  if (colorGrading_) {
    engine_->destroy(colorGrading_);
    colorGrading_ = nullptr;
  }
}

void Compositor::resolveBackground(const std::string& envOverride) {
  // The backdrop is a swappable, room-like vertical-gradient environment cubemap (HDRI-style
  // ceiling/wall/floor) so the light Material panels pop. Selection precedence, most → least
  // specific:
  //   1. CLI `--environment <name>`     → a named preset (see kPresets)
  //      CLI `--environment color:#RRGGBB` → a flat-colour skybox
  //   2. scene `environment`:
  //        kind=="color"                 → flat colour (uses `color`)
  //        else                          → `preset` selects a named preset; explicit
  //                                        `sky`/`horizon`/`floor`/`glow` OVERRIDE its values
  //   3. Built-in default = `warm-room`.
  // `bg_` doubles as the clear colour for the readback path.
  bool wantColor = false;
  float3 colorBg = {0.06f, 0.06f, 0.08f};

  const GradientPreset* preset = findPreset(kDefaultPreset);
  float3 gradSky = hexToLinear(preset->sky);
  float3 gradHorizon = hexToLinear(preset->horizon);
  float3 gradFloor = hexToLinear(preset->floor);
  bool gradHasFloor = preset->hasFloor;
  float gradGlow = preset->glow;

  auto applyPreset = [&](const GradientPreset* p) {
    gradSky = hexToLinear(p->sky);
    gradHorizon = hexToLinear(p->horizon);
    gradFloor = hexToLinear(p->floor);
    gradHasFloor = p->hasFloor;
    gradGlow = p->glow;
  };

  // (2) scene environment.
  if (model_.environment) {
    const auto& env = *model_.environment;
    if (env.kind == "color") {
      wantColor = true;
      if (env.color) colorBg = hexToLinear(*env.color);
    } else {
      if (env.preset) {
        if (const GradientPreset* p = findPreset(*env.preset)) applyPreset(p);
        else fprintf(stderr, "unknown environment preset '%s'; using default\n", env.preset->c_str());
      }
      if (env.sky) gradSky = hexToLinear(*env.sky);
      if (env.horizon) gradHorizon = hexToLinear(*env.horizon);
      if (env.floor) {
        gradFloor = hexToLinear(*env.floor);
        gradHasFloor = true;
      }
      if (env.glow) gradGlow = static_cast<float>(*env.glow);
    }
  }

  // (1) CLI override — highest precedence.
  if (!envOverride.empty()) {
    if (envOverride.rfind("color:", 0) == 0) {
      wantColor = true;
      colorBg = hexToLinear(envOverride.substr(6));
    } else if (const GradientPreset* p = findPreset(envOverride)) {
      wantColor = false;
      applyPreset(p);
    } else {
      fprintf(stderr, "unknown --environment '%s'; using scene/default backdrop\n",
              envOverride.c_str());
    }
  }

  if (skybox_) {
    scene_->setSkybox(nullptr);
    engine_->destroy(skybox_);
    skybox_ = nullptr;
  }
  if (wantColor) {
    bg_ = colorBg;
    skybox_ = Skybox::Builder().color({bg_.r, bg_.g, bg_.b, 1.0f}).build(*engine_);
  } else {
    bg_ = gradHorizon;  // clear colour mirrors the horizon so uncovered edges blend in
    skybox_ = buildGradientSkybox(*engine_, gradSky, gradHorizon, gradFloor, gradHasFloor, gradGlow);
    if (!skybox_) {
      fprintf(stderr, "gradient skybox build failed; falling back to solid\n");
      skybox_ = Skybox::Builder().color({bg_.r, bg_.g, bg_.b, 1.0f}).build(*engine_);
    }
  }
  scene_->setSkybox(skybox_);
}

Texture* Compositor::loadTexture(const std::string& relTexture, const std::string& fallbackId) {
  std::string texRel = relTexture.empty() ? (fallbackId + ".png") : relTexture;
  std::string texPath = sceneDir_ + "/" + texRel;
  auto it = texCache_.find(texPath);
  if (it != texCache_.end()) return it->second;

  // Filament readPixels on this swapchain is top-origin and UV0 (0,0) maps to the quad's
  // bottom-left, so loading the PNG top-down (no stb flip) keeps panel content upright.
  stbi_set_flip_vertically_on_load(false);
  int tw, th, tn;
  unsigned char* pix = stbi_load(texPath.c_str(), &tw, &th, &tn, 4);
  if (!pix) {
    fprintf(stderr, "  panel %s: no texture %s (skipped)\n", fallbackId.c_str(), texPath.c_str());
    return nullptr;
  }
  size_t texBytes = (size_t)tw * th * 4;
  Texture* tex = Texture::Builder()
      .width(tw).height(th).levels(0xff)
      .format(Texture::InternalFormat::SRGB8_A8)
      .usage(Texture::Usage::SAMPLEABLE | Texture::Usage::UPLOADABLE |
             Texture::Usage::GEN_MIPMAPPABLE)
      .sampler(Texture::Sampler::SAMPLER_2D)
      .build(*engine_);
  Texture::PixelBufferDescriptor pbd(
      pix, texBytes, Texture::Format::RGBA, Texture::Type::UBYTE,
      [](void* buf, size_t, void*) { stbi_image_free(buf); }, nullptr);
  tex->setImage(*engine_, 0, std::move(pbd));
  tex->generateMipmaps(*engine_);
  texCache_[texPath] = tex;
  return tex;
}

// Build a textured/coloured quad renderable spanning [-hw,hw] x [-hh,hh] in the XY plane, UV0
// running (0,0) bottom-left to (1,1) top-right. The GPU vertex/index buffers are tracked for teardown
// on the next rebuild; their CPU backing is freed in the descriptor callbacks.
utils::Entity Compositor::buildQuad(float hw, float hh, MaterialInstance* mi) {
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
      .build(*engine_);
  vb->setBufferAt(*engine_, 0, VertexBuffer::BufferDescriptor(
      verts, sizeof(Vertex) * 4, [](void* p, size_t, void*) { delete[] (Vertex*)p; }));
  IndexBuffer* ib = IndexBuffer::Builder()
      .indexCount(6).bufferType(IndexBuffer::IndexType::USHORT).build(*engine_);
  ib->setBuffer(*engine_, IndexBuffer::BufferDescriptor(
      idx, sizeof(uint16_t) * 6, [](void* p, size_t, void*) { delete[] (uint16_t*)p; }));
  utils::Entity e = utils::EntityManager::get().create();
  RenderableManager::Builder(1)
      .boundingBox({{-hw, -hh, -1.0f}, {hw, hh, 1.0f}})
      .material(0, mi)
      .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, vb, ib, 0, 6)
      .culling(false).castShadows(false).receiveShadows(false)
      .build(*engine_, e);
  vbs_.push_back(vb);
  ibs_.push_back(ib);
  return e;
}

void Compositor::clearPanels() {
  for (auto e : panelEntities_) {
    scene_->remove(e);
    engine_->destroy(e);
    utils::EntityManager::get().destroy(e);
  }
  for (auto* m : mis_) engine_->destroy(m);
  for (auto* v : vbs_) engine_->destroy(v);
  for (auto* i : ibs_) engine_->destroy(i);
  panelEntities_.clear();
  mis_.clear();
  vbs_.clear();
  ibs_.clear();
}

void Compositor::rebuildPanels() {
  clearPanels();
  auto& tcm = engine_->getTransformManager();
  static TextureSampler sampler(TextureSampler::MinFilter::LINEAR_MIPMAP_LINEAR,
                                TextureSampler::MagFilter::LINEAR);
  int placed = 0;
  for (const auto& panel : model_.panels) {
    Texture* tex = loadTexture(panel.texture, panel.id);
    if (!tex) continue;

    float wDp = static_cast<float>(panel.sizeDp.width);
    float hDp = static_cast<float>(panel.sizeDp.height);
    float hw = wDp * 0.5f, hh = hDp * 0.5f;

    // Fidelity params evaluated in an aspect-corrected "rect space" where the panel spans
    // [-halfSize, halfSize] and rounded corners stay circular regardless of aspect (see the .mat).
    float aspect = (hDp > 0.0f) ? (wDp / hDp) : 1.0f;
    float2 halfSize = (wDp >= hDp) ? float2{aspect, 1.0f} : float2{1.0f, 1.0f / aspect};
    const float kCornerRadius = 0.18f;
    const float kRimWidth = 0.06f;
    const float kRimStrength = 0.10f;
    const float kEdgeSoftness = 0.012f;

    MaterialInstance* mi = unlit_->createInstance();
    mi->setParameter("albedo", tex, sampler);
    mi->setParameter("halfSize", halfSize);
    mi->setParameter("cornerRadius", kCornerRadius);
    mi->setParameter("rimWidth", kRimWidth);
    mi->setParameter("rimStrength", kRimStrength);
    mi->setParameter("edgeSoftness", kEdgeSoftness);
    mis_.push_back(mi);

    // transform: translate * rotate(quat)
    const auto& T = panel.poseInRoot.translation;
    const auto& R = panel.poseInRoot.rotation;
    float3 t = {static_cast<float>(T.x), static_cast<float>(T.y), static_cast<float>(T.z)};
    quatf q = {static_cast<float>(R.w), static_cast<float>(R.x), static_cast<float>(R.y),
               static_cast<float>(R.z)};
    mat4f rot(q);

    // ---- soft drop/contact shadow behind+below the panel ----
    {
      float dpPerUnitX = hw / halfSize.x;
      float blurUnits = 0.22f;
      float2 outerHalf = halfSize + float2{blurUnits, blurUnits};
      float shw = outerHalf.x * dpPerUnitX;
      float shh = outerHalf.y * dpPerUnitX;

      MaterialInstance* smi = shadowMat_->createInstance();
      smi->setParameter("halfSize", halfSize);
      smi->setParameter("cornerRadius", kCornerRadius);
      smi->setParameter("blur", blurUnits);
      smi->setParameter("color", float4{0.0f, 0.0f, 0.0f, 0.42f});
      mis_.push_back(smi);

      utils::Entity se = buildQuad(shw, shh, smi);
      float3 shadowOffset = {0.0f, -0.06f * hDp, -2.0f};
      mat4f shadowModel = mat4f::translation(t) * rot * mat4f::translation(shadowOffset);
      tcm.setTransform(tcm.getInstance(se), shadowModel);
      scene_->addEntity(se);
      panelEntities_.push_back(se);
    }

    utils::Entity re = buildQuad(hw, hh, mi);
    mat4f model = mat4f::translation(t) * rot;
    tcm.setTransform(tcm.getInstance(re), model);
    scene_->addEntity(re);
    panelEntities_.push_back(re);
    placed++;
    fprintf(stderr, "  panel %s: pos(%.0f,%.0f,%.0f) size %.0fx%.0f\n",
            panel.id.c_str(), t.x, t.y, t.z, wDp, hDp);
  }
  fprintf(stderr, "placed %d panel(s)\n", placed);
}

void Compositor::setupCamera() {
  const auto& cam = model_.camera;
  float3 target = {static_cast<float>(cam.target.x), static_cast<float>(cam.target.y),
                   static_cast<float>(cam.target.z)};
  double distance = cam.distance;
  double yaw = cam.yawDeg * kPi / 180.0;
  double pitch = cam.pitchDeg * kPi / 180.0;
  float3 dir = {(float)(std::cos(pitch) * std::sin(yaw)), (float)std::sin(pitch),
                (float)(std::cos(pitch) * std::cos(yaw))};
  float3 eye = target + dir * (float)distance;
  camera_->lookAt(eye, target, {0, 1, 0});
  double aspect = (double)W_ / (double)H_;
  camera_->setProjection(45.0, aspect, 1.0, distance * 10.0 + 10000.0, Camera::Fov::VERTICAL);
}

void Compositor::setScene(const xrcomposite::SpatialScene& scene, const std::string& sceneDir,
                          const std::string& envOverride) {
  model_ = scene;
  sceneDir_ = sceneDir.empty() ? "." : sceneDir;
  resolveBackground(envOverride);
  rebuildPanels();
  setupCamera();
}

void Compositor::applyPanelUpdates(const json& panels) {
  if (!panels.is_array()) return;
  for (const auto& u : panels) {
    if (!u.contains("id")) continue;
    std::string id = u.at("id").get<std::string>();
    auto it = std::find_if(model_.panels.begin(), model_.panels.end(),
                           [&](const xrcomposite::SpatialPanel& p) { return p.id == id; });
    if (it == model_.panels.end()) {
      // A new panel: parse the whole entry and append (must carry the required fields).
      try {
        model_.panels.push_back(u.get<xrcomposite::SpatialPanel>());
      } catch (const std::exception& e) {
        fprintf(stderr, "updatePanels: unknown panel '%s' and not a full panel (%s)\n",
                id.c_str(), e.what());
      }
      continue;
    }
    if (u.contains("texture")) it->texture = u.at("texture").get<std::string>();
    if (u.contains("poseInRoot")) it->poseInRoot = u.at("poseInRoot").get<xrcomposite::SpatialPose>();
    if (u.contains("sizeDp")) it->sizeDp = u.at("sizeDp").get<xrcomposite::SizeDp>();
  }
  rebuildPanels();
}

std::vector<uint8_t> Compositor::renderPixels() {
  Renderer::ClearOptions co;
  co.clear = true;
  co.clearColor = {bg_.r, bg_.g, bg_.b, 1.0f};
  renderer_->setClearOptions(co);

  std::vector<uint8_t> pixels((size_t)W_ * H_ * 4);
  struct Capture { bool done; } capture{false};
  if (renderer_->beginFrame(swapChain_)) {
    renderer_->render(view_);
    backend::PixelBufferDescriptor pb(
        pixels.data(), pixels.size(),
        backend::PixelDataFormat::RGBA, backend::PixelDataType::UBYTE,
        [](void*, size_t, void* user) { ((Capture*)user)->done = true; }, &capture);
    renderer_->readPixels(0, 0, W_, H_, std::move(pb));
    renderer_->endFrame();
  } else {
    fprintf(stderr, "beginFrame returned false\n");
  }
  engine_->flushAndWait();
  return pixels;
}

bool Compositor::writePng(const std::string& path) {
  auto pixels = renderPixels();
  // Filament's readPixels here returns top-origin rows, matching PNG's top-left origin — no flip.
  if (!stbi_write_png(path.c_str(), W_, H_, 4, pixels.data(), W_ * 4)) {
    fprintf(stderr, "png write failed\n");
    return false;
  }
  fprintf(stderr, "wrote %s (%ux%u)\n", path.c_str(), W_, H_);
  return true;
}

// Directory containing a scene file (for resolving relative panel textures). A bare filename has no
// '/', so default to ".".
std::string dirOf(const std::string& path) {
  auto slash = path.find_last_of('/');
  return (slash == std::string::npos) ? "." : path.substr(0, slash);
}

// ---- JSON-RPC over stdio (LSP-style Content-Length framing) ----

bool readMessage(std::string& body) {
  size_t contentLength = 0;
  std::string line;
  bool sawHeader = false;
  while (std::getline(std::cin, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.empty()) {
      if (sawHeader) break;  // blank line terminates headers
      continue;              // tolerate leading blank lines
    }
    sawHeader = true;
    auto colon = line.find(':');
    if (colon != std::string::npos) {
      std::string key = line.substr(0, colon);
      std::string val = line.substr(colon + 1);
      // trim leading spaces
      size_t s = val.find_first_not_of(" \t");
      if (s != std::string::npos) val = val.substr(s);
      std::transform(key.begin(), key.end(), key.begin(), ::tolower);
      if (key == "content-length") contentLength = std::stoul(val);
    }
  }
  if (contentLength == 0) return false;  // EOF or framing end
  body.resize(contentLength);
  std::cin.read(&body[0], (std::streamsize)contentLength);
  return (size_t)std::cin.gcount() == contentLength;
}

void writeMessage(const json& msg) {
  std::string s = msg.dump();
  std::cout << "Content-Length: " << s.size() << "\r\n\r\n" << s;
  std::cout.flush();
}

void writeResult(const json& id, const json& result) {
  writeMessage({{"jsonrpc", "2.0"}, {"id", id}, {"result", result}});
}

void writeError(const json& id, int code, const std::string& message) {
  writeMessage({{"jsonrpc", "2.0"}, {"id", id}, {"error", {{"code", code}, {"message", message}}}});
}

// Emit one rendered frame as a `streamFrame` notification (base64 PNG), reusing the daemon's
// composestream/1 shape. `seq` is a monotonic frame counter.
void emitStreamFrame(Compositor& comp, uint64_t seq, const std::string& sessionId) {
  auto pixels = comp.renderPixels();
  auto png = encodePng(pixels, comp.width(), comp.height());
  json params = {
      {"encoding", "png"},
      {"width", comp.width()},
      {"height", comp.height()},
      {"seq", seq},
      {"data", base64Encode(png.data(), png.size())},
  };
  // The session this frame belongs to, so a multiplexing client can demux frames from the one
  // shared process. Mirrored as `frameStreamId` for the daemon's existing stream-frame shape.
  if (!sessionId.empty()) {
    params["sessionId"] = sessionId;
    params["frameStreamId"] = sessionId;
  }
  writeMessage({{"jsonrpc", "2.0"}, {"method", "streamFrame"}, {"params", params}});
}

int runServer(const Args& args) {
  SharedGl gl;
  if (!gl.create(args.materialsDir)) return 3;
  fprintf(stderr, "xr-composite: serving on stdio (multi-session, one shared engine)\n");

  // One session per `sessionId` (the daemon's frameStreamId), all sharing `gl`'s engine.
  std::map<std::string, std::unique_ptr<Compositor>> sessions;
  uint64_t seq = 0;
  std::string body;
  // Single-session clients register a stream id once at `initialize` and then omit it on subsequent
  // calls; remember it so their frames stay tagged with that id rather than the literal "default".
  std::string defaultSessionId = "default";

  // sessionId precedence: explicit `sessionId`, then per-call `frameStreamId`, else the
  // initialize-registered default (back-compat with single-session callers and the one-shot smoke).
  auto sessionIdOf = [&](const json& params) -> std::string {
    return params.value("sessionId", params.value("frameStreamId", defaultSessionId));
  };

  while (readMessage(body)) {
    json msg;
    try {
      msg = json::parse(body);
    } catch (const std::exception& e) {
      writeError(nullptr, -32700, std::string("parse error: ") + e.what());
      continue;
    }
    json id = msg.contains("id") ? msg["id"] : json(nullptr);
    std::string method = msg.value("method", "");
    const json& params = msg.contains("params") ? msg["params"] : json::object();

    if (method == "initialize") {
      defaultSessionId = params.value("frameStreamId", defaultSessionId);
      writeResult(id, {
          {"serverInfo", {{"name", "xr-composite"}, {"version", 1}}},
          {"capabilities", {
              {"render", true},
              {"updatePanels", true},
              {"streamFrame", true},
              {"multiSession", true},
              {"spatialSceneVersion", xrcomposite::SPATIAL_SCENE_VERSION},
              {"dataProducts", json::array({"xr/composite"})},
          }},
      });
    } else if (method == "render" || method == "xr/render") {
      try {
        const std::string sid = sessionIdOf(params);
        auto scene = params.at("scene").get<xrcomposite::SpatialScene>();
        std::string sceneDir = params.value("sceneDir", ".");
        std::string envOverride = params.value("environment", "");
        uint32_t w = params.value("width", args.width);
        uint32_t h = params.value("height", args.height);
        // (Re)create the session if absent or if its viewport changed.
        auto it = sessions.find(sid);
        if (it != sessions.end() && (it->second->width() != w || it->second->height() != h)) {
          it->second->teardown();
          sessions.erase(it);
          it = sessions.end();
        }
        if (it == sessions.end()) {
          auto comp = std::make_unique<Compositor>();
          if (!comp->init(gl, w, h)) {
            if (!id.is_null()) writeError(id, -32603, "session init failed");
            continue;
          }
          it = sessions.emplace(sid, std::move(comp)).first;
        }
        Compositor& comp = *it->second;
        comp.setScene(scene, sceneDir, envOverride);
        if (params.contains("out")) comp.writePng(params.at("out").get<std::string>());
        emitStreamFrame(comp, ++seq, sid);
        if (!id.is_null())
          writeResult(id, {{"ok", true}, {"seq", seq}, {"sessionId", sid},
                           {"width", comp.width()}, {"height", comp.height()}});
      } catch (const std::exception& e) {
        if (!id.is_null()) writeError(id, -32602, std::string("render failed: ") + e.what());
      }
    } else if (method == "xr/updatePanels") {
      const std::string sid = sessionIdOf(params);
      auto it = sessions.find(sid);
      if (it == sessions.end()) {
        if (!id.is_null()) writeError(id, -32002, "no scene: call render first for session " + sid);
        continue;
      }
      try {
        if (params.contains("panels")) it->second->applyPanelUpdates(params.at("panels"));
        if (params.contains("out")) it->second->writePng(params.at("out").get<std::string>());
        emitStreamFrame(*it->second, ++seq, sid);
        if (!id.is_null()) writeResult(id, {{"ok", true}, {"seq", seq}, {"sessionId", sid}});
      } catch (const std::exception& e) {
        if (!id.is_null()) writeError(id, -32602, std::string("updatePanels failed: ") + e.what());
      }
    } else if (method == "xr/stop") {
      const std::string sid = sessionIdOf(params);
      auto it = sessions.find(sid);
      if (it != sessions.end()) {
        it->second->teardown();
        sessions.erase(it);
      }
      if (!id.is_null()) writeResult(id, {{"ok", true}, {"sessionId", sid}});
    } else if (method == "shutdown") {
      if (!id.is_null()) writeResult(id, json::object());
    } else if (method == "exit") {
      break;
    } else if (!method.empty()) {
      if (!id.is_null()) writeError(id, -32601, "unknown method: " + method);
    }
  }
  fprintf(stderr, "xr-composite: stdio closed, exiting\n");
  fflush(stderr);
  // Skip Filament teardown (asserts on destroy order); the process exits here.
  std::_Exit(0);
}

int runOneShot(const Args& args) {
  xrcomposite::SpatialScene scene;
  {
    std::ifstream f(args.scenePath);
    json raw;
    f >> raw;
    scene = raw.get<xrcomposite::SpatialScene>();
  }
  SharedGl gl;
  if (!gl.create(args.materialsDir)) return 3;
  Compositor comp;
  if (!comp.init(gl, args.width, args.height)) return 3;
  comp.setScene(scene, dirOf(args.scenePath), args.environment);
  if (!comp.writePng(args.outPath)) return 4;
  fflush(stderr);
  // std::_Exit terminates without running destructors/atexit (portable equivalent of POSIX _exit);
  // avoids Filament's teardown-order asserts on all platforms.
  std::_Exit(0);
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
    else if (a == "--serve") args.serve = true;
  }
  if (args.materialsDir.empty()) args.materialsDir = ".";

  if (args.serve) return runServer(args);

  if (args.scenePath.empty()) {
    fprintf(stderr, "usage: --scene scene.json --out out.png   (or --serve for JSON-RPC stdio)\n");
    return 2;
  }
  return runOneShot(args);
}
