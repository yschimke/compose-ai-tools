// GENERATED FILE — DO NOT EDIT.
// Source of truth: schema/spatial-scene.schema.json
// Regenerate: node scripts/codegen/gen-spatial-scene.mjs (CI checks with --check).

#pragma once

#include <optional>
#include <string>
#include <vector>

#include "json.hpp"

namespace xrcomposite {

using nlohmann::json;

/**
 * The wire contract between the offline renderer (producer) and the webview's 3D spatial-layout viewer (consumer). All linear quantities are dp; axes are right-handed (+x right, +y up, +z toward the viewer); rotation is a unit quaternion. Prose spec: docs/design/SPATIAL_SCENE_CONTRACT.md.
 */
constexpr int SPATIAL_SCENE_VERSION = 1;

/**
 * A point or vector, in dp.
 */
struct Vec3 {
  double x;
  double y;
  double z;
};

/**
 * A unit quaternion; identity is `(0, 0, 0, 1)`.
 */
struct Quat {
  double x;
  double y;
  double z;
  double w;
};

/**
 * A rigid transform in the subspace root's frame: `translation` in dp, `rotation` a unit
 * quaternion.
 */
struct SpatialPose {
  Vec3 translation;
  Quat rotation;
};

/**
 * Panel extent in dp (the layout output, not the texture's pixel size).
 */
struct SizeDp {
  int width;
  int height;
};

/**
 * A spatial panel: a flat quad hosting 2D Compose content.
 */
struct SpatialPanel {
  std::string id;
  /**
   * Human-readable label for overlays; not required for rendering.
   */
  std::optional<std::string> label;
  SpatialPose poseInRoot;
  SizeDp sizeDp;
  /**
   * Path to the panel's 2D-content PNG, relative to the scene file (or a resolvable URI).
   */
  std::string texture;
  /**
   * Id of the containing panel/group, or null/omitted for top-level panels.
   */
  std::optional<std::string> parentId;
};

/**
 * An Orbiter affordance — a control strip anchored to a panel edge. `edge` is
 * top/bottom/start/end.
 */
struct OrbiterAffordance {
  std::string id;
  std::optional<std::string> label;
  std::string edge;
  SpatialPose poseInRoot;
  SizeDp sizeDp;
  std::string texture;
};

/**
 * Default viewing camera. Only `kind = "orbit"` is defined today.
 */
struct OrbitCamera {
  std::string kind = "orbit";
  /**
   * Look-at point in dp.
   */
  Vec3 target;
  /**
   * Camera distance from `target`, in dp.
   */
  double distance;
  double yawDeg;
  double pitchDeg;
};

/**
 * Optional scene backdrop. `kind` is "color" (`#RRGGBB` in [color]) or "skybox" ([texture]).
 *
 * For gradient backdrops (any `kind` other than "color"), the offline compositor supports **named
 * presets** ([preset], e.g. `"warm-room"` — the default — or `"studio-dark"`) plus explicit
 * gradient stops that **override** the chosen preset: [sky] (straight up), [horizon] (eye level),
 * and [floor] (straight down; its presence turns the 2-stop gradient into a 3-stop, room-like one).
 * These knobs are optional; omit them to take the compositor's default `warm-room` backdrop. The
 * compositor's `--environment` CLI flag overrides whatever the scene specifies.
 */
struct SpatialEnvironment {
  std::string kind;
  std::optional<std::string> color;
  std::optional<std::string> texture;
  /**
   * Named gradient preset (e.g. `"warm-room"`, `"studio-dark"`); ignored when `kind == "color"`.
   */
  std::optional<std::string> preset;
  /**
   * Gradient colour straight up (`#RRGGBB`); overrides the preset.
   */
  std::optional<std::string> sky;
  /**
   * Gradient colour at eye level (`#RRGGBB`); overrides the preset. Doubles as the clear colour.
   */
  std::optional<std::string> horizon;
  /**
   * Gradient colour straight down (`#RRGGBB`); overrides the preset and enables a 3-stop floor.
   */
  std::optional<std::string> floor;
  /**
   * Gradient glow intensity; overrides the preset's glow. Consumed by the native compositor's room backdrop.
   */
  std::optional<double> glow;
};

/**
 * The full scene the 3D viewer renders. [version] must equal [SPATIAL_SCENE_VERSION].
 */
struct SpatialScene {
  /**
   * Bumped on breaking changes. Producers stamp it into `SpatialScene.version`.
   */
  int version = SPATIAL_SCENE_VERSION;
  /**
   * All linear quantities are dp.
   */
  std::string units = "dp";
  /**
   * The preview this scene was projected from, if any.
   */
  std::optional<std::string> previewId;
  OrbitCamera camera;
  std::vector<SpatialPanel> panels;
  std::vector<OrbiterAffordance> orbiters;
  std::optional<SpatialEnvironment> environment;
};

inline void from_json(const json& j, Vec3& x) {
  j.at("x").get_to(x.x);
  j.at("y").get_to(x.y);
  j.at("z").get_to(x.z);
}

inline void to_json(json& j, const Vec3& x) {
  j = json::object();
  j["x"] = x.x;
  j["y"] = x.y;
  j["z"] = x.z;
}

inline void from_json(const json& j, Quat& x) {
  j.at("x").get_to(x.x);
  j.at("y").get_to(x.y);
  j.at("z").get_to(x.z);
  j.at("w").get_to(x.w);
}

inline void to_json(json& j, const Quat& x) {
  j = json::object();
  j["x"] = x.x;
  j["y"] = x.y;
  j["z"] = x.z;
  j["w"] = x.w;
}

inline void from_json(const json& j, SpatialPose& x) {
  j.at("translation").get_to(x.translation);
  j.at("rotation").get_to(x.rotation);
}

inline void to_json(json& j, const SpatialPose& x) {
  j = json::object();
  j["translation"] = x.translation;
  j["rotation"] = x.rotation;
}

inline void from_json(const json& j, SizeDp& x) {
  j.at("width").get_to(x.width);
  j.at("height").get_to(x.height);
}

inline void to_json(json& j, const SizeDp& x) {
  j = json::object();
  j["width"] = x.width;
  j["height"] = x.height;
}

inline void from_json(const json& j, SpatialPanel& x) {
  j.at("id").get_to(x.id);
  if (j.contains("label") && !j.at("label").is_null()) x.label = j.at("label").get<std::string>();
  j.at("poseInRoot").get_to(x.poseInRoot);
  j.at("sizeDp").get_to(x.sizeDp);
  j.at("texture").get_to(x.texture);
  if (j.contains("parentId") && !j.at("parentId").is_null()) x.parentId = j.at("parentId").get<std::string>();
}

inline void to_json(json& j, const SpatialPanel& x) {
  j = json::object();
  j["id"] = x.id;
  if (x.label) j["label"] = *x.label;
  j["poseInRoot"] = x.poseInRoot;
  j["sizeDp"] = x.sizeDp;
  j["texture"] = x.texture;
  if (x.parentId) j["parentId"] = *x.parentId;
}

inline void from_json(const json& j, OrbiterAffordance& x) {
  j.at("id").get_to(x.id);
  if (j.contains("label") && !j.at("label").is_null()) x.label = j.at("label").get<std::string>();
  j.at("edge").get_to(x.edge);
  j.at("poseInRoot").get_to(x.poseInRoot);
  j.at("sizeDp").get_to(x.sizeDp);
  j.at("texture").get_to(x.texture);
}

inline void to_json(json& j, const OrbiterAffordance& x) {
  j = json::object();
  j["id"] = x.id;
  if (x.label) j["label"] = *x.label;
  j["edge"] = x.edge;
  j["poseInRoot"] = x.poseInRoot;
  j["sizeDp"] = x.sizeDp;
  j["texture"] = x.texture;
}

inline void from_json(const json& j, OrbitCamera& x) {
  if (j.contains("kind") && !j.at("kind").is_null()) j.at("kind").get_to(x.kind);
  j.at("target").get_to(x.target);
  j.at("distance").get_to(x.distance);
  j.at("yawDeg").get_to(x.yawDeg);
  j.at("pitchDeg").get_to(x.pitchDeg);
}

inline void to_json(json& j, const OrbitCamera& x) {
  j = json::object();
  j["kind"] = x.kind;
  j["target"] = x.target;
  j["distance"] = x.distance;
  j["yawDeg"] = x.yawDeg;
  j["pitchDeg"] = x.pitchDeg;
}

inline void from_json(const json& j, SpatialEnvironment& x) {
  j.at("kind").get_to(x.kind);
  if (j.contains("color") && !j.at("color").is_null()) x.color = j.at("color").get<std::string>();
  if (j.contains("texture") && !j.at("texture").is_null()) x.texture = j.at("texture").get<std::string>();
  if (j.contains("preset") && !j.at("preset").is_null()) x.preset = j.at("preset").get<std::string>();
  if (j.contains("sky") && !j.at("sky").is_null()) x.sky = j.at("sky").get<std::string>();
  if (j.contains("horizon") && !j.at("horizon").is_null()) x.horizon = j.at("horizon").get<std::string>();
  if (j.contains("floor") && !j.at("floor").is_null()) x.floor = j.at("floor").get<std::string>();
  if (j.contains("glow") && !j.at("glow").is_null()) x.glow = j.at("glow").get<double>();
}

inline void to_json(json& j, const SpatialEnvironment& x) {
  j = json::object();
  j["kind"] = x.kind;
  if (x.color) j["color"] = *x.color;
  if (x.texture) j["texture"] = *x.texture;
  if (x.preset) j["preset"] = *x.preset;
  if (x.sky) j["sky"] = *x.sky;
  if (x.horizon) j["horizon"] = *x.horizon;
  if (x.floor) j["floor"] = *x.floor;
  if (x.glow) j["glow"] = *x.glow;
}

inline void from_json(const json& j, SpatialScene& x) {
  if (j.contains("version") && !j.at("version").is_null()) j.at("version").get_to(x.version);
  if (j.contains("units") && !j.at("units").is_null()) j.at("units").get_to(x.units);
  if (j.contains("previewId") && !j.at("previewId").is_null()) x.previewId = j.at("previewId").get<std::string>();
  j.at("camera").get_to(x.camera);
  if (j.contains("panels")) j.at("panels").get_to(x.panels);
  if (j.contains("orbiters")) j.at("orbiters").get_to(x.orbiters);
  if (j.contains("environment") && !j.at("environment").is_null()) x.environment = j.at("environment").get<SpatialEnvironment>();
}

inline void to_json(json& j, const SpatialScene& x) {
  j = json::object();
  j["version"] = x.version;
  j["units"] = x.units;
  if (x.previewId) j["previewId"] = *x.previewId;
  j["camera"] = x.camera;
  j["panels"] = x.panels;
  j["orbiters"] = x.orbiters;
  if (x.environment) j["environment"] = *x.environment;
}

}  // namespace xrcomposite
