import * as path from "path";
import { globSync } from "glob";

const repoRoot = path.resolve(__dirname, "../..");
const sourceTestRoot = path.join(repoRoot, "src", "test");
const compiledTestRoot = path.join(repoRoot, "out", "test");

const sourceTests = globSync("**/*.test.ts", {
    cwd: sourceTestRoot,
    ignore: ["electron/**"],
});

for (const sourceTest of sourceTests) {
    require(path.join(compiledTestRoot, sourceTest.replace(/\.ts$/, ".js")));
}
