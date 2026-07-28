#!/usr/bin/env python3
"""Tests for lib/post-comment.sh, the sticky-comment upsert helper.

The script is pure shell against the `gh` CLI, so the tests drive it with a
`gh` double on PATH that keeps the PR's comments in a JSON file. That is
enough to pin the behaviour that actually broke in issue #2869: a PATCH that
sent `@_comment_body.md` as a literal string, wiping the marker off the
sticky comment so every later run posted a fresh duplicate.

Supersedes `lib/test-post-comment.sh` (issue #2868), whose three cases are
carried over below. That double answered every listing call with the same
id, which can't express a script that now lists three times for three
different questions — does a corpse exist, does the sticky comment exist,
did the write land. The store here is stateful, so those are distinct.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "lib" / "post-comment.sh"
MARKER = "<!-- preview-diff -->"

# `gh` double. Implements just the calls post-comment.sh makes, including the
# `-F body=@file` vs `-f body=…` distinction that is the whole point of the
# issue: -F expands the file, -f sends the string verbatim.
FAKE_GH = r'''#!/usr/bin/env python3
import json, os, re, sys

state = os.environ["FAKE_GH_STATE"]
argv = sys.argv[1:]
with open(state) as fh:
    comments = json.load(fh)

def save():
    with open(state, "w") as fh:
        json.dump(comments, fh)

def log(entry):
    with open(os.environ["FAKE_GH_LOG"], "a") as fh:
        fh.write(json.dumps(entry) + "\n")

def field_value(flag):
    """Resolve a -f/-F value the way gh does."""
    for i, a in enumerate(argv):
        if a == flag:
            raw = argv[i + 1]
            key, _, val = raw.partition("=")
            if flag == "-F" and val.startswith("@"):
                with open(val[1:]) as fh:
                    return fh.read()
            return val
    return None

def jq_ids(expr):
    """Emulate the two filters post-comment.sh uses."""
    out = []
    for c in comments:
        if "startswith(" in expr:
            marker = re.search(r'startswith\("(.*)"\)', expr).group(1)
            if c["body"].startswith(marker):
                out.append(c["id"])
        elif ".body ==" in expr:
            placeholder = re.search(r'\.body == "(.*?)"', expr).group(1)
            if c.get("user", {}).get("type") != "Bot":
                continue
            if c["body"] == placeholder:
                out.append(c["id"])
    return out

if argv[0] == "api" and argv[1].endswith("/comments") and "--jq" in argv:
    expr = argv[argv.index("--jq") + 1]
    for cid in jq_ids(expr):
        print(cid)
    sys.exit(0)

if argv[0] == "api" and "-X" in argv:
    method = argv[argv.index("-X") + 1]
    cid = int(argv[1].rsplit("/", 1)[-1])
    if method == "DELETE":
        log({"call": "delete", "id": cid})
        comments[:] = [c for c in comments if c["id"] != cid]
        save()
        sys.exit(0)
    if method == "PATCH":
        body = field_value("-F")
        if body is None:
            body = field_value("-f")
        log({"call": "patch", "id": cid, "body": body})
        for c in comments:
            if c["id"] == cid:
                c["body"] = body
        save()
        print("{}")
        sys.exit(0)

if argv[0] == "pr" and argv[1] == "comment":
    with open(argv[argv.index("--body-file") + 1]) as fh:
        body = fh.read()
    log({"call": "create", "body": body})
    comments.append({"id": 900 + len(comments), "body": body,
                     "user": {"type": "Bot"}})
    save()
    sys.exit(0)

sys.stderr.write("fake gh: unhandled %r\n" % (argv,))
sys.exit(2)
'''


class PostCommentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        bindir = self.tmp / "bin"
        bindir.mkdir()
        gh = bindir / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        self.state = self.tmp / "state.json"
        self.log = self.tmp / "log.jsonl"
        self.log.touch()
        self.env = dict(os.environ)
        self.env.update(
            PATH=f"{bindir}:{os.environ['PATH']}",
            REPO="acme/widgets",
            PR_NUMBER="42",
            MARKER=MARKER,
            GH_TOKEN="t",
            FAKE_GH_STATE=str(self.state),
            FAKE_GH_LOG=str(self.log),
        )

    def run_script(self, body, comments, expect_rc=0, env=None):
        self.state.write_text(json.dumps(comments))
        body_file = self.tmp / "_comment_body.md"
        body_file.write_text(body)
        env = {**self.env, **(env or {}), "BODY_FILE": str(body_file)}
        proc = subprocess.run(
            ["bash", str(SCRIPT)], cwd=self.tmp, env=env,
            capture_output=True, text=True,
        )
        self.assertEqual(proc.returncode, expect_rc, proc.stderr)
        return proc

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def comments(self):
        return json.loads(self.state.read_text())

    def test_posts_when_no_sticky_comment_exists(self):
        body = f"{MARKER}\n## Preview\n\nchanged.\n"
        self.run_script(body, [])
        self.assertEqual([c["call"] for c in self.calls()], ["create"])
        self.assertEqual(self.comments()[0]["body"], body)

    def test_patch_sends_the_file_contents_not_the_placeholder(self):
        """Regression for issue #2869: -f sent the literal `@…md` path."""
        body = f"{MARKER}\n## Preview\n\nupdated.\n"
        existing = [{"id": 7, "body": f"{MARKER}\nold", "user": {"type": "Bot"}}]
        self.run_script(body, existing)
        patch = self.calls()[0]
        self.assertEqual(patch["call"], "patch")
        self.assertEqual(patch["body"], body)
        self.assertFalse(patch["body"].startswith("@"))
        # The marker survives, so the next run still finds this comment
        # instead of posting a second one.
        self.assertEqual(len(self.comments()), 1)
        self.assertTrue(self.comments()[0]["body"].startswith(MARKER))

    def test_repeated_runs_keep_exactly_one_comment(self):
        comments = []
        for i in range(3):
            self.state.write_text(json.dumps(comments))
            self.run_script(f"{MARKER}\nrun {i}\n", comments)
            comments = self.comments()
        self.assertEqual(len(comments), 1)
        self.assertIn("run 2", comments[0]["body"])

    def test_deletes_orphaned_placeholder_comments(self):
        corpse = {"id": 5, "body": "@_comment_body.md", "user": {"type": "Bot"}}
        self.run_script(f"{MARKER}\nfresh\n", [corpse])
        calls = self.calls()
        self.assertEqual(calls[0], {"call": "delete", "id": 5})
        bodies = [c["body"] for c in self.comments()]
        self.assertEqual(len(bodies), 1)
        self.assertTrue(bodies[0].startswith(MARKER))

    def test_leaves_comments_that_are_not_our_own_placeholder(self):
        # A human comment with the same text; a bot comment that merely
        # mentions a .md file; and another integration's placeholder — none
        # of these are this action's corpse, so none may be deleted.
        others = [
            {"id": 5, "body": "@_comment_body.md", "user": {"type": "User"}},
            {"id": 6, "body": "see @notes.md for context",
             "user": {"type": "Bot"}},
            {"id": 7, "body": "@reports/result.md", "user": {"type": "Bot"}},
        ]
        self.run_script(f"{MARKER}\nfresh\n", others)
        self.assertNotIn("delete", [c["call"] for c in self.calls()])
        self.assertEqual(len(self.comments()), 4)

    def test_only_cleans_the_current_pipelines_placeholder(self):
        # The a11y run must not delete the compose run's corpse: each
        # invocation owns exactly the placeholder its own BODY_FILE names.
        corpses = [
            {"id": 5, "body": "@_comment_body.md", "user": {"type": "Bot"}},
            {"id": 6, "body": "@_a11y_comment.md", "user": {"type": "Bot"}},
        ]
        self.state.write_text(json.dumps(corpses))
        body_file = self.tmp / "_a11y_comment.md"
        body_file.write_text(f"{MARKER}\nfresh\n")
        proc = subprocess.run(
            ["bash", str(SCRIPT)], cwd=self.tmp,
            env={**self.env, "BODY_FILE": str(body_file)},
            capture_output=True, text=True,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual([c for c in self.calls() if c["call"] == "delete"],
                         [{"call": "delete", "id": 6}])
        self.assertIn("@_comment_body.md",
                      [c["body"] for c in self.comments()])

    def test_fails_loudly_when_the_body_does_not_land(self):
        """A write that loses the marker must go red, not post duplicates."""
        body = f"{MARKER}\nupdated\n"
        existing = [{"id": 7, "body": f"{MARKER}\nold", "user": {"type": "Bot"}}]
        # Simulate the old bug by pointing the double at a gh that keeps -f
        # semantics: the marker is gone after the write.
        broken = self.tmp / "bin" / "gh"
        broken.write_text(FAKE_GH.replace('body = field_value("-F")',
                                          "body = None"))
        broken.chmod(0o755)
        proc = self.run_script(body, existing, expect_rc=1)
        self.assertIn("did not land intact", proc.stderr)

    def test_truncates_an_over_long_body(self):
        body = MARKER + "\n" + "x\n" * 5000
        self.run_script(body, [], env={"COMMENT_MAX_BYTES": "2000"})
        posted = self.comments()[0]["body"]
        self.assertLessEqual(len(posted.encode()), 2000)
        self.assertTrue(posted.startswith(MARKER))
        self.assertIn("truncated", posted)


if __name__ == "__main__":
    unittest.main()
