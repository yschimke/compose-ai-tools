plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.wire)
}

// The emulator `EmulatorController` gRPC service (a subset) + screenshot video stream. Square Wire
// generates the protobuf message classes from the vendored
// `src/main/proto/emulator_controller.proto`
// (pure-Kotlin, no protoc); the gRPC server is bound by hand into grpc-netty via grpc-java's
// `ServerCalls` + a small Wire `MethodDescriptor.Marshaller`. Isolated here so the codegen
// toolchain
// stays out of every other module. Unpublished tooling. See docs/fake-emulator/DESIGN.md.
wire {
  kotlin {
    // Messages only — we hand-roll the gRPC service binding, so skip Wire's service codegen.
    rpcRole = "none"
  }
}

dependencies {
  api(project(":fake-emulator-core"))
  api(libs.wire.runtime)
  api(libs.grpc.stub)
  implementation(libs.grpc.netty.shaded)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
