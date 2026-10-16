"""The Java providers and JDK these rules use; internal deployments may substitute this shim.

Every rules_java load in this repository goes through here, so a deployment whose
Java rules live elsewhere edits this one file and nothing else.
"""

load("@rules_java//java/common:java_info.bzl", _JavaInfo = "JavaInfo")
load("@rules_java//java/common/rules:java_runtime.bzl", _JavaRuntimeInfo = "JavaRuntimeInfo")

JavaInfo = _JavaInfo

JavaRuntimeInfo = _JavaRuntimeInfo

# The Java 17+ runtime the rules' own actions run on (the R-class compile, the producer and
# renderer launchers). A Label, so it resolves from these rules' repository wherever they load.
JDK_RUNTIME = Label("@rules_java//toolchains:remotejdk_17")
