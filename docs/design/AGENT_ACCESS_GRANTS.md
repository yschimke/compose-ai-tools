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

An approver must be a **human operator of this box**, which means passing the server's own front
door:

- GitHub auth configured ⇒ a signed-in visitor, subject to `--github-auth-users` as usual. The grant
  records their login, so `/status` and the server log say *who* let the agent in.
- No GitHub auth ⇒ the holder of `--token` (the URL the operator already has in their browser).
  Recorded as `operator (token)`.

A `--public` server with **no** GitHub auth has no approver identity at all — everyone is anonymous
and equal — so `--agent-grants` is refused at startup there rather than silently letting the
internet mint itself credentials.

## Lifetime, revocation, blast radius

- **Request TTL** 10 minutes. A link nobody opens dies quickly.
- **Grant TTL** requested by the agent, capped by `--agent-grant-max-ttl` (default 8h, hard ceiling
  24h). Chosen by the approver on the page, so "give it 20 minutes" is available without the agent
  re-asking.
- **Revocation** from `/status` (one button per live grant), by the agent itself
  (`POST /agent-access/revoke`), and implicitly at expiry.
- **Bounded** — `--agent-grant-max-active` (default 16) live grants, oldest-expiry evicted first;
  requests likewise. The store is in memory, so a restart drops every grant. That is deliberate: the
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

`--no-wait` prints the link and exits, for an agent that would rather not hold a process open; a
later `auth request --resume` (or just `auth status`) collects the token.
