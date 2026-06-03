// xr-composite — render a SpatialScene (scene.json + panel PNGs) to a composite PNG, headless.
// Spike: Filament OpenGL backend on llvmpipe under Xvfb. No GPU required.

// Third-party headers FIRST: Filament's utils/debug.h defines an `assert_invariant`
// macro that otherwise clobbers nlohmann::json's member function of the same name.
#include "json.hpp"
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
  }
  if (args.scenePath.empty()) { fprintf(stderr, "usage: --scene scene.json --out out.png\n"); return 2; }
  // A bare filename (no '/') means the scene lives in the current directory; find_last_of
  // returns npos there, so default to "." rather than treating the filename as the directory.
  auto slash = args.scenePath.find_last_of('/');
  args.sceneDir = (slash == std::string::npos) ? "." : args.scenePath.substr(0, slash);
  if (args.materialsDir.empty()) args.materialsDir = ".";

  // ---- parse scene.json ----
  json scene;
  { std::ifstream f(args.scenePath); f >> scene; }

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
  float3 bg = {0.06f, 0.06f, 0.08f};
  if (scene.contains("environment") && scene["environment"].is_object()) {
    auto& env = scene["environment"];
    if (env.value("kind", "") == "color" && env.contains("color"))
      bg = hexToLinear(env["color"].get<std::string>());
  }
  Skybox* skybox = Skybox::Builder().color({bg.r, bg.g, bg.b, 1.0f}).build(*engine);
  fscene->setSkybox(skybox);

  // ---- material ----
  auto unlitPkg = readFile(args.materialsDir + "/unlit_texture.filamat");
  Material* unlit = Material::Builder().package(unlitPkg.data(), unlitPkg.size()).build(*engine);

  TextureSampler sampler(TextureSampler::MinFilter::LINEAR_MIPMAP_LINEAR,
                         TextureSampler::MagFilter::LINEAR);

  auto& tcm = engine->getTransformManager();

  // ---- panels ----
  int placed = 0;
  for (auto& p : scene["panels"]) {
    std::string id = p.value("id", "panel");
    std::string texRel = p.value("texture", id + ".png");
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

    MaterialInstance* mi = unlit->createInstance();
    mi->setParameter("albedo", tex, sampler);

    float wDp = p["sizeDp"].value("width", 100);
    float hDp = p["sizeDp"].value("height", 100);
    float hw = wDp * 0.5f, hh = hDp * 0.5f;

    // Heap-allocate: Filament reads the BufferDescriptor pointer on the driver thread during
    // flushAndWait (after this loop), so the data must outlive the iteration — free in the callback.
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

    utils::Entity re = utils::EntityManager::get().create();
    RenderableManager::Builder(1)
        .boundingBox({{-hw, -hh, -1.0f}, {hw, hh, 1.0f}})
        .material(0, mi)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, vb, ib, 0, 6)
        .culling(false).castShadows(false).receiveShadows(false)
        .build(*engine, re);

    // transform: translate * rotate(quat)
    auto& T = p["poseInRoot"]["translation"];
    auto& R = p["poseInRoot"]["rotation"];
    float3 t = {T.value("x", 0.0f), T.value("y", 0.0f), T.value("z", 0.0f)};
    quatf q = {R.value("w", 1.0f), R.value("x", 0.0f), R.value("y", 0.0f), R.value("z", 0.0f)};
    mat4f model = mat4f::translation(t) * mat4f(q);
    tcm.setTransform(tcm.getInstance(re), model);

    fscene->addEntity(re);
    placed++;
    fprintf(stderr, "  panel %s: pos(%.0f,%.0f,%.0f) size %.0fx%.0f tex %dx%d\n",
            id.c_str(), t.x, t.y, t.z, wDp, hDp, tw, th);
  }
  fprintf(stderr, "placed %d panel(s)\n", placed);

  // ---- camera (orbit) ----
  auto& cam = scene["camera"];
  float3 target = {cam["target"].value("x", 0.0f), cam["target"].value("y", 0.0f),
                   cam["target"].value("z", 0.0f)};
  double distance = cam.value("distance", 1200.0);
  double yaw = cam.value("yawDeg", 0.0) * kPi / 180.0;
  double pitch = cam.value("pitchDeg", -10.0) * kPi / 180.0;
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
