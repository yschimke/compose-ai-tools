// Coverage for the History panel's reporting-branch enumeration (#1872).
//
// `listReportingBranches` shells out to `git for-each-ref`, so we synthesise a
// throwaway repo with a mix of `preview/*` and non-preview branches and assert
// only the reporting branches surface, mapped to friendly labels. `git` is
// required; tests skip cleanly when it's unavailable (mirrors the daemon-side
// GitRefHistorySource tests).

import * as assert from "assert";
import { execFileSync } from "child_process";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

import {
    listReportingBranches,
    reportingBranchLabel,
} from "../reportingBranches";

function gitAvailable(): boolean {
    try {
        execFileSync("git", ["--version"], { stdio: "ignore" });
        return true;
    } catch {
        return false;
    }
}

function git(repo: string, ...args: string[]): void {
    execFileSync("git", ["-C", repo, ...args], { stdio: "ignore" });
}

describe("reportingBranchLabel", () => {
    it("maps local preview heads to their short name", () => {
        assert.strictEqual(
            reportingBranchLabel("refs/heads/preview/main"),
            "preview/main",
        );
    });

    it("maps remote preview branches to remote/preview/name", () => {
        assert.strictEqual(
            reportingBranchLabel("refs/remotes/origin/preview/feature-x"),
            "origin/preview/feature-x",
        );
    });

    it("returns null for non-preview refs", () => {
        assert.strictEqual(reportingBranchLabel("refs/heads/main"), null);
        assert.strictEqual(
            reportingBranchLabel("refs/remotes/origin/main"),
            null,
        );
        assert.strictEqual(reportingBranchLabel("refs/tags/v1"), null);
    });
});

describe("listReportingBranches", () => {
    let repo: string;
    const hasGit = gitAvailable();

    beforeEach(function () {
        if (!hasGit) {
            this.skip();
        }
        repo = fs.mkdtempSync(path.join(os.tmpdir(), "reporting-branches-"));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "commit.gpgsign", "false");
        fs.writeFileSync(path.join(repo, "README"), "init");
        git(repo, "add", "README");
        git(repo, "commit", "-q", "-m", "init");
    });

    afterEach(() => {
        if (repo) {
            fs.rmSync(repo, { recursive: true, force: true });
        }
    });

    it("returns only preview/* branches, sorted, with friendly labels", async () => {
        git(repo, "branch", "preview/main");
        git(repo, "branch", "preview/feature-x");
        git(repo, "branch", "feature/unrelated");

        const branches = await listReportingBranches(repo);

        assert.deepStrictEqual(
            branches.map((b) => b.label),
            ["preview/feature-x", "preview/main"],
        );
        assert.deepStrictEqual(
            branches.map((b) => b.ref),
            ["refs/heads/preview/feature-x", "refs/heads/preview/main"],
        );
    });

    it("includes remote preview branches", async () => {
        // Synthesise a remote-tracking ref without a real remote by writing the
        // packed/loose ref directly under refs/remotes.
        git(repo, "branch", "preview/main");
        const remoteDir = path.join(repo, ".git", "refs", "remotes", "origin");
        fs.mkdirSync(remoteDir, { recursive: true });
        const sha = execFileSync(
            "git",
            ["-C", repo, "rev-parse", "refs/heads/preview/main"],
            { encoding: "utf8" },
        ).trim();
        fs.writeFileSync(
            path.join(remoteDir, "preview"),
            "", // placeholder; real ref written below as preview/main
        );
        fs.rmSync(path.join(remoteDir, "preview"));
        fs.mkdirSync(path.join(remoteDir, "preview"), { recursive: true });
        fs.writeFileSync(path.join(remoteDir, "preview", "main"), sha + "\n");

        const branches = await listReportingBranches(repo);
        const labels = branches.map((b) => b.label);
        assert.ok(
            labels.includes("preview/main"),
            "local preview/main present",
        );
        assert.ok(
            labels.includes("origin/preview/main"),
            "remote origin/preview/main present",
        );
    });

    it("returns [] when there are no preview branches", async () => {
        git(repo, "branch", "feature/x");
        assert.deepStrictEqual(await listReportingBranches(repo), []);
    });

    it("returns [] for a non-git directory", async () => {
        const notRepo = fs.mkdtempSync(path.join(os.tmpdir(), "not-a-repo-"));
        try {
            assert.deepStrictEqual(await listReportingBranches(notRepo), []);
        } finally {
            fs.rmSync(notRepo, { recursive: true, force: true });
        }
    });
});
