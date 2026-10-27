# Discord capture harness

Read-only tooling used to produce the reference data in [`docs/gui`](../../docs/gui).
It runs against the **live Discord web client** in the in-app browser; it never clicks, types,
sends or mutates anything.

Nothing here is part of the build. `composeApp` must not depend on this folder.

> Discord's UI, icon art and fonts are their IP. Use these captures to check **measurements and
> behaviour**, and ship your own assets. See `docs/gui/README.md` §Legal.

---

## Files

| File                     | Purpose                                                                                                                                                         |
|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `collector.js`           | Injected into the page. Exposes `window.__YAP` with `tokens()`, `spec()`, `spec2()`, `specAll()`, `tree()`, `icons()`, `hexOf()`, `normalize()`, `typeScale()`. |
| `resolve_tokens.py`      | Turns the raw CSS-variable dump into flat sRGB (`#rrggbb` + alpha).                                                                                             |
| `emit_compose_tokens.py` | Generates `docs/gui/reference/YapTheme.kt` from the resolved tokens.                                                                                            |
| `cssq.py`                | Queries a dumped stylesheet bundle for the rules of a given class (base/hover/active/media).                                                                    |
| `.cache/`                | Scratch space for the raw CSS dump. Git-ignored; regenerate on demand.                                                                                          |

---

## Why the numbers are trustworthy

Discord ships hashed class names (`.button_a22cb0`) but a complete CSS custom-property system.
Two independent sources were used and cross-checked:

1. **Computed styles** of real elements (`getComputedStyle`) — geometry, actual rendered colour.
2. **Authored CSSOM** (`document.styleSheets[i].cssRules`, all 726 sheets are same-origin and
   readable) — including `:hover`, `:active`, `:disabled` and media-query variants that you cannot
   trigger by hand.

Values were normalised to sRGB with a 1×1 canvas, because Chrome serialises modern colours as
`oklab()`/`color-mix()`.

---

## Re-running

1. Open Discord in the app browser and log in. Navigate to the screen you want.
2. Inject the collector:

   ```js
   // paste the whole file, then:
   __YAP.tokens()                      // { theme, count, vars }
   __YAP.spec2('.channel__972a0')      // normalised computed style of an element
   __YAP.tree('.container__5e434', 9)  // geometry tree
   __YAP.icons()                       // inline SVG path data
   __YAP.typeScale()                   // heading-*/body-* tokens in use
   ```

3. Pull a large result out without flooding the transcript — download it from the page and copy it
   to the server:

   ```js
   const blob = new Blob([JSON.stringify(__YAP.tokens(), null, 2)], { type: 'application/json' });
   const a = document.createElement('a');
   a.href = URL.createObjectURL(blob); a.download = 'discord-tokens.json'; a.click();
   ```

   then use the browser tool's `files.get` to place it on disk.

4. Dump the stylesheet bundle (needed only if you want to inspect states):

   ```js
   const out = [];
   const walk = (rules, ctx) => { for (const r of rules) {
     if (r.cssRules && r.selectorText === undefined) walk(r.cssRules, ctx + ' | ' + (r.conditionText || ''));
     else out.push(ctx + ' | ' + r.cssText);
   }};
   for (const s of document.styleSheets) { try { walk(s.cssRules, ''); } catch {} }
   // write `out.join('\n')` in ~4 MB chunks via Blob downloads
   ```

   Save the concatenated result to `tools/discord-capture/.cache/discord-css.txt`.

5. Regenerate the derived artifacts:

   ```bash
   python3 tools/discord-capture/resolve_tokens.py
   python3 tools/discord-capture/emit_compose_tokens.py
   python3 tools/discord-capture/cssq.py button_a22cb0
   ```

---

## Known limits

- The accessibility snapshot of the Discord web app is effectively unusable, so the harness drives
  everything through `evaluate` + CSS/DOM queries. Synthetic mouse events are ignored by the app (it checks
  `isTrusted`), so hover/context-menu states are recovered from the authored CSS rather
  than by interaction.
- Screens behind authentication require a logged-in session; do not capture anyone else's account.
- Class-name hashes change between Discord builds. Everything under `docs/gui` records the values,
  not the selectors, so a rebuild only invalidates the *queries*, not the design data.
