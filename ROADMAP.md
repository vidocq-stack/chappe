# Chappe — Roadmap

Forward-looking items for Chappe. Shipped behaviour is documented in `README.md` and `CLAUDE.md`;
this file tracks deliberate decisions to defer or design features, so the rationale is not lost.

---

## Static serving — URL mapping / rewrite + redirect table

### Shipped (context)

`StaticFileHandler.cleanUrls` — a *convention*: an extensionless request that matches no file or
directory index is retried with a `.html` suffix, so `/admin` and `/admin/` both serve `admin.html`.
Exposed on static mounts via `vidocq.http.mount.<name>.clean-urls`. This closed Arago `ARAGO-006`
(serve the admin console at a clean `/admin` without a per-app redirect). It mirrors nginx
`try_files $uri.html`, Caddy `try_files`, and Netlify "pretty URLs".

### Planned

An explicit, ordered **mapping table** for the arbitrary cases the `cleanUrls` convention cannot
express: `/foo` → `/bar.html`, legacy path moves, several distinct entries per mount. Most static-server
ecosystems ship *both* a convention (try_files / extensions / pretty-urls) **and** an explicit
rewrite/redirect table — the table is the ~10% escape hatch, kept orthogonal to the convention.

### Design notes & open questions

Captured from the design discussion so a future implementer starts informed:

- **rewrite vs redirect** — two distinct concerns, likely two property families:
  - *rewrite* — internal, status `200`, URL unchanged in the browser (e.g. `rewrites=`).
  - *redirect* — status `301`/`302`/`307`/`308`, URL changes (e.g. `redirects=`).
- **Home of the feature** — a generic **Router / `Filter`-level** mechanism applicable to *all* mount
  types (`static`, `restful`, `websocket`), **not** folded into `StaticFileHandler`: an arbitrary
  `path → path` mapping (especially with a redirect, or `/old-api` → `/api/v2`) is a routing concern,
  not a static-file concern. Chappe already has the primitives — `StatusCode` `301/302/307/308`, the
  `Filter` interface, `Response.builder().header("Location", …)` — what is missing is a Router
  redirect/rewrite helper to compose them declaratively.
- **Config form** — prefer a **CSV list value**
  (`rewrites=/admin:/admin.html,/login:/login.html`) over slash-bearing, quoted property *keys*
  (`mapping."/admin"=…`). Quoted keys with `/` are not portable across MicroProfile Config sources —
  environment variables in particular (Arago overrides config via env vars in Docker). The mount config
  already parses CSV lists via `MountConfig.properties(suffix, type)`.
- **Open** — wildcard / glob / regex support, ordering & precedence between rules, trailing-slash
  handling, interaction with `cleanUrls` (convention applied before or after explicit rules?).
- **Precedent** — nginx (`try_files` + `rewrite`/`location`), Apache (`mod_rewrite` + `MultiViews`),
  Caddy (`try_files` + `redir`), Netlify/Vercel ("pretty URLs" + `_redirects` / `rewrites[]`).

---

## Other known future targets

Already noted elsewhere; listed here for a coherent roadmap.

- **HTTP/3 (RFC 9114)** — QUIC transport via the JDK (targeting JDK 26+). See `CLAUDE.md`.
- **`vidocq-servlet`** — Servlet 6.1 extension on top of Chappe. See `README.md` ("coming soon").
- **`vidocq-jaxrs`** — native JAX-RS 4.0 extension on top of Chappe. See `README.md` ("coming soon").
