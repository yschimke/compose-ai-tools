# Agent access grants

**Status:** implemented (`compose-preview serve --agent-grants`, `compose-preview auth …`)

## The problem

`compose-preview serve` protects itself two ways, and an agent can satisfy neither.

- A private box is **token-gated**: every route runs through `rejectBadToken`, which wants
  `--token`'s value in `?token=` or `X-Compose-Preview-Token`. Handing that to an agent hands over
  the whole server, forever, in a string that then lives in the agent's transcript.
- A public box is **GitHub-gated**: live preview wants a signed-in visitor
  (`rejectMissingGithubAuth`), the playground additionally wants access to `--github-auth-repo`
  (`rejectMissingGithubRepoAccess`). Both are cookie sessions minted by an interactive OAuth
  redirect. An agent has no browser to be redirected in, and asking a human to paste their session
  cookie is worse than the token.

So the practical answer today is "paste the operator token into the agent's context", which is a
permanent, unscoped, unrevocable, unattributable credential. That is the thing this replaces.

## The shape

The [OAuth 2.0 Device Authorization Grant (RFC 8628)](https://datatracker.ietf.org/doc/html/rfc8628),
narrowed to this server. It is exactly the right shape and for exactly the reason it was invented:
the party that needs the credential cannot render the authorization page, so it asks for a **link**
that someone who *can* render it will open.

```
agent                                   serve                              human's browser
  │                                       │                                       │
  │ POST /agent-access/request            │                                       │
  │  {label, scopes, ttlSeconds}          │                                       │
  ├──────────────────────────────────────►│                                       │
  │  {requestId, deviceSecret, userCode,  │                                       │
  │   approveUrl, pollUrl, …}             │                                       │
  │◄──────────────────────────────────────┤                                       │
  │                                       │                                       │
  │ prints approveUrl + userCode ─────────┼──────────────────────────────────────►│
  │                                       │  GET /agent-access/{requestId}        │
  │                                       │◄──────────────────────────────────────┤
  │                                       │  (GitHub sign-in, if configured)      │
  │                                       │  approval page: who, what, how long   │
  │                                       ├──────────────────────────────────────►│
  │                                       │  POST …/approve {scopes, ttl, csrf}   │
  │                                       │◄──────────────────────────────────────┤
  │ POST /agent-access/poll               │                                       │
  │  {requestId, deviceSecret}            │                                       │
  ├──────────────────────────────────────►│                                       │
  │  {status: "approved", token, …}       │                                       │
  │◄──────────────────────────────────────┤                                       │
```

The agent then presents that token the same way the operator token is presented — `?token=` or
`X-Compose-Preview-Token` — so **no route changes shape**. The four existing gates learn one new
way to say yes.

## Why the token is not in the link

This is the design's load-bearing property, and it is the reason the flow has two secrets rather
than one.

The link is going to be pasted into a chat window, a terminal, an issue comment, a Slack DM. It will
be logged by something. If the link *were* the credential, every one of those is a compromise.

So the link carries only `requestId` — a public handle. The token is delivered on the **poll** leg,
to whoever proves possession of `deviceSecret`, which never leaves the agent's process except in the
body of a POST to the server that minted it. A leaked link therefore buys an attacker nothing they
can use: the worst they can do is *approve* a request whose token is then handed to the agent that
asked for it, which is the outcome the human wanted anyway.

That is also why approval is a `POST`, never a `GET` with a magic query parameter. A link-unfurler,
a prefetcher, a corporate mail scanner, or an over-helpful chat client fetching the URL to build a
preview card must not be able to grant access by looking at it.

## Why there is a user code

`userCode` (`WXYZ-1234`) is printed by the agent *and* displayed on the approval page, and the human
is told to check that they match.

Without it the flow has a real hole: an attacker who can get a message in front of the operator
sends *their own* approval link, styled as the agent's. The operator, who is genuinely expecting to
approve something right now, clicks it and approves — and the attacker's poller collects the token.
The code closes it, because the attacker cannot make the agent's terminal print the attacker's code.
This is the same control, for the same reason, as the one on a TV's sign-in screen.

## Scopes, and the ceiling on them

Three, ordered, each implying the ones below:

| Scope        | Unlocks                                             | Gate it satisfies                |
|--------------|-----------------------------------------------------|----------------------------------|
| `preview`    | browse catalogs, fetch baked renders, `/status`     | `rejectBadToken`                 |
| `live`       | live daemon streaming, the viewer WebSocket         | `rejectMissingGithubAuth`        |
| `playground` | compile and run a snippet on the box                | `rejectMissingGithubRepoAccess`  |

Two details of *how* those gates read a grant are load-bearing rather than incidental.

**Scope is checked before, and independently of, whether GitHub auth exists.** Written the other way
round — `githubAuth ?: return false` first — a private box with no OAuth configured let every grant
through the live and playground gates unread, because those gates had nothing else to say. A
presented grant is now judged on its own scope on every deployment shape, and a grant that falls
short gets a 403 naming the scope it lacks rather than a sign-in redirect it has no browser for.

**A route that commissions a render wants `live`, even when it looks like a read.**
`/render/{id}.png` replays baked bytes — until an override query or a non-PNG suffix turns it into
an on-demand daemon render, and `/bundle.zip` renders the whole catalog. Those are the CPU cost
`live` exists to describe, so they check the scope themselves; a bare replay stays `preview`,
because refusing it would break ordinary browsing for a grant that was given exactly that.

**The ingest lanes are outside the scope system entirely.** `POST /bundles/{name}` and `POST /docs`
run through the same `rejectBadToken` as everything else, so a `preview` grant would have satisfied
them — letting an agent granted "browse this server's catalogs" publish a document or replace a
named runtime bundle. They take [`rejectBadTokenForIngest`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHttpServer.kt)
instead, which no grant satisfies. Contributing content to someone else's box is the operator's
business, not a capability this flow should be able to hand out; the image lane already worked this
way for its own reasons.

Two ceilings apply, and both are enforced at approval rather than at request:

1. **The operator's ceiling** — `--agent-grant-scopes`, default `preview,live`. `playground` runs
   attacker-chosen Kotlin on the host, so it is never in a default grant; an operator who wants
   agents to reach it says so once, on the command line.
2. **The approver's ceiling** — an approver can never grant what they do not themselves hold. On a
   GitHub-gated box, approving `playground` requires the approver's own session to carry
   `repositoryAccess`; without it the checkbox is disabled and a forged POST is refused.

A request asks for scopes; the approval page is where they are actually chosen. The human may
approve less than was asked for, and the page defaults to exactly what was asked for so the ordinary
case is one click.

## Who may approve

An approver must be a **human operator of this box**, and that is checked in two parts, both of
which have to pass:

1. **The server's own front door.** On a non-`--public` box the approver must present `--token`. A
   GitHub session is *not* a substitute for it: on a private box that also configures OAuth, any
   account the (by default empty) `--github-auth-users` allowlist accepts could otherwise open an
   ungated request, sign in at its own approval URL, and mint itself a grant into a server whose
   browse token it never had.
2. **An identity, where there is one to have.** GitHub auth configured ⇒ a signed-in visitor,
   recorded by login, so `/status` and the server log say *who* let the agent in. No GitHub auth ⇒
   the `--token` holder from step 1, recorded as `operator (token)`.

Neither part can be satisfied by an agent grant — a GitHub session lives in a cookie no agent holds,
and the token compare is against `--token` specifically, which no minted bearer can equal. So a
grant can never approve or revoke another.

A `--public` server with **no** GitHub auth has neither part: everyone is anonymous, and there is no
front door to pass. `--agent-grants` is refused at startup there rather than silently letting the
internet mint itself credentials.

## Lifetime, revocation, blast radius

- **Request TTL** 10 minutes. A link nobody opens dies quickly.
- **Grant TTL** requested by the agent, capped by `--agent-grant-max-ttl` (default 8h, hard ceiling
  24h). Chosen by the approver on the page, so "give it 20 minutes" is available without the agent
  re-asking.
- **Revocation** from `/status` (one button per live grant), by the agent itself
  (`POST /agent-access/revoke`), and implicitly at expiry.
- **Bounded** — `--agent-grant-max-active` (default 16) live grants, nearest-expiry evicted first
  *excluding the one just minted* (an approver choosing a short lifetime on a full box would
  otherwise evict the grant the page had just reported as approved). Requests are bounded too, and
  shed only once denied or collected — an approval nobody has polled for yet still owes its owner a
  credential. The store is in memory, so a restart drops every grant. That is deliberate: the
  TTLs are short, and a credential that cannot survive a redeploy has a much smaller worst case than
  one that can.
- **Never printed.** The token appears exactly once, in the poll response. `/status`, the server log,
  and every error message carry only a fingerprint (`sha256` prefix). The audit line names the
  approver, the label, the scopes, the expiry, and that fingerprint.

## What it is not

- **Not a session.** No cookies, no refresh, no sliding expiry. It ends when it ends.
- **Not an identity.** A grant does not become a GitHub login for the purposes of anything that
  writes — the image-upload lane still wants a real GitHub credential, because what it does with one
  is push to a repository.
- **Not admin.** `--admin-token` routes are outside every scope. Nothing an agent can be granted
  reconfigures the box.

## Client side

`compose-preview auth` drives the agent's half:

```
compose-preview auth request --server https://preview.coo.ee \
    --scope live --ttl 2h --label "fix wear-m3-catalog#68"
compose-preview auth status
compose-preview auth token      # prints the bearer, for scripting
compose-preview auth revoke
```

`request` prints the link and the code, then blocks on the poll until the human approves, and stores
the granted token in `~/.config/compose-preview/agent-access.json` (mode `0600`), keyed by server
origin. Every other CLI lane that talks to a serve host resolves its host token from
`--token` → `$COMPOSE_PREVIEW_TOKEN` → that store, so once a grant lands the rest of the CLI simply
starts working.

The store is written through a temp file and an atomic rename, under an advisory lock on a sibling
`.lock`. Both halves matter with more than one agent on a machine: without the lock two runs
finishing together each read the old list and the later writer drops the other's freshly approved
grant; without the atomic replace a concurrent reader can see a half-written file, report it as
empty, and then overwrite it.

**`--no-wait` persists the request** — the id, the device secret, the link and the code — so a later
`auth status` (or `auth token`) polls it and promotes the approval into a grant. Without that the
printed "re-run auth status when they approve" was an instruction nobody could follow: the device
secret is the only thing that can redeem an approval, and it had been thrown away. It is saved
before anything is printed, so an interrupted wait is recoverable too.

**`auth status` asks the server rather than trusting the file.** It collects any remembered request,
and verifies each stored grant with `whoami`: one the operator revoked, or one that died with a
server restart, is reported as gone and dropped instead of being listed as live until its local
expiry and then failing somewhere else with an unexplained 404. A server it cannot reach yields
`unverified`, never a deletion — an unreachable host is not evidence that a grant is dead.

**`--json` is JSON Lines**, one compact document per line. A waiting `auth request --json` emits two
— the request, so the link can be relayed immediately, and the grant once it lands — and two
pretty-printed objects concatenated would not parse as anything.
