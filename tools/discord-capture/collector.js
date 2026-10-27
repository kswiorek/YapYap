/*
 * YapYap GUI spec collector — inject into the Discord web app tab.
 *
 * Usage (browser eval):
 *   <paste this file>
 *   JSON.stringify(__YAP.tokens())
 *   JSON.stringify(__YAP.spec('selector'))
 *   JSON.stringify(__YAP.tree('#app-mount', 8))
 *
 * Read-only: it never clicks, types, or mutates the page.
 * Everything is returned as JSON-serializable data.
 */
window.__YAP = (function () {
    const PROPS = [
        // layout
        'display', 'position', 'flexDirection', 'flexWrap', 'justifyContent', 'alignItems',
        'alignSelf', 'gap', 'rowGap', 'columnGap', 'gridTemplateColumns', 'gridTemplateRows',
        'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
        'marginTop', 'marginRight', 'marginBottom', 'marginLeft',
        'width', 'height', 'minWidth', 'minHeight', 'maxWidth', 'maxHeight',
        'top', 'right', 'bottom', 'left', 'zIndex', 'overflowX', 'overflowY',
        // typography
        'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight', 'letterSpacing',
        'textTransform', 'textAlign', 'textDecorationLine', 'whiteSpace', 'wordBreak',
        'color', 'caretColor',
        // paint
        'backgroundColor', 'backgroundImage', 'borderTopWidth', 'borderRightWidth',
        'borderBottomWidth', 'borderLeftWidth', 'borderTopColor', 'borderRightColor',
        'borderBottomColor', 'borderLeftColor', 'borderTopLeftRadius', 'borderTopRightRadius',
        'borderBottomLeftRadius', 'borderBottomRightRadius', 'boxShadow', 'outlineColor',
        'outlineWidth', 'outlineOffset',
        // effects
        'opacity', 'filter', 'backdropFilter', 'transform', 'transitionProperty',
        'transitionDuration', 'transitionTimingFunction', 'cursor', 'pointerEvents',
    ];

    const px = (v) => {
        const n = parseFloat(v);
        return Number.isFinite(n) ? n : v;
    };

    function styleOf(el, pseudo) {
        const cs = getComputedStyle(el, pseudo || null);
        const o = {};
        for (const p of PROPS) {
            const v = cs[p];
            if (v === '' || v == null) continue;
            if (v === 'none' || v === 'normal' || v === 'auto' || v === '0px') {
                // keep a few meaningful defaults out to reduce noise
                if (p !== 'display' && p !== 'position') continue;
            }
            o[p] = /^-?[\d.]+px$/.test(v) ? px(v) : v;
        }
        return o;
    }

    function box(el) {
        const r = el.getBoundingClientRect();
        return {
            x: Math.round(r.x), y: Math.round(r.y),
            w: Math.round(r.width), h: Math.round(r.height),
            cx: Math.round(r.x + r.width / 2), cy: Math.round(r.y + r.height / 2),
        };
    }

    const cls = (el) => {
        if (!el) return '';
        const c = el.className;
        if (typeof c === 'string') return c;
        if (c && c.baseVal !== undefined) return String(c.baseVal);
        return '';
    };

    function label(el) {
        if (!el) return '';
        return (
            el.getAttribute('aria-label') ||
            el.getAttribute('title') ||
            el.getAttribute('placeholder') ||
            ''
        ).slice(0, 80);
    }

    function textOf(el, max) {
        const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
        return t.slice(0, max || 120);
    }

    // ---------------------------------------------------------------- tokens
    function tokens() {
        const out = {};
        const collect = (el) => {
            const cs = getComputedStyle(el);
            for (let i = 0; i < cs.length; i++) {
                const name = cs[i];
                if (name.startsWith('--')) {
                    const v = cs.getPropertyValue(name).trim();
                    if (v && !(name in out)) out[name] = v;
                }
            }
        };
        collect(document.documentElement);
        collect(document.body);
        // the theme container also defines semantic vars that :root may not
        const themeHost = document.querySelector('.theme-dark, .theme-light, [class*="theme-"]');
        if (themeHost) collect(themeHost);
        // every large surface, in case a pane scopes its own theme
        document.querySelectorAll('[class*="theme-"]').forEach((el) => {
            if (el.getBoundingClientRect().width > 300) collect(el);
        });
        return {
            theme: {
                htmlClass: document.documentElement.className,
                htmlStyle: document.documentElement.getAttribute('style'),
                bodyClass: document.body.className,
                fontSizeRoot: getComputedStyle(document.documentElement).fontSize,
            },
            count: Object.keys(out).length,
            vars: out,
        };
    }

    // ---------------------------------------------------------------- spec
    function spec(target) {
        const el = typeof target === 'string' ? document.querySelector(target) : target;
        if (!el) return null;
        const parts = [];
        for (const pseudo of ['::before', '::after', '::placeholder']) {
            const s = getComputedStyle(el, pseudo);
            // pseudo elements report 'none' content when absent
            if (pseudo !== '::placeholder' && (s.content === 'none' || s.content === 'normal')) continue;
            if (pseudo === '::placeholder') {
                const color = s.color;
                if (color) parts.push({pseudo, color, opacity: s.opacity});
                continue;
            }
            parts.push({pseudo, style: styleOf(el, pseudo), content: s.content});
        }
        const r = el.getBoundingClientRect();
        return {
            selector: typeof target === 'string' ? target : null,
            tag: el.tagName.toLowerCase(),
            cls: cls(el).slice(0, 200),
            role: el.getAttribute('role'),
            label: label(el),
            text: textOf(el, 160),
            box: box(el),
            style: styleOf(el),
            pseudos: parts,
            scroll: {
                scrollHeight: el.scrollHeight,
                clientHeight: el.clientHeight,
                scrollWidth: el.scrollWidth,
                clientWidth: el.clientWidth,
            },
            parent: el.parentElement ? {
                tag: el.parentElement.tagName.toLowerCase(),
                cls: cls(el.parentElement).slice(0, 120)
            } : null,
        };
    }

    function specAll(selector, limit) {
        return Array.from(document.querySelectorAll(selector)).slice(0, limit || 20).map(spec);
    }

    // ---------------------------------------------------------------- geometry tree
    function tree(target, maxDepth, minSize) {
        const root = typeof target === 'string' ? document.querySelector(target) : target;
        const out = [];
        const walk = (el, d) => {
            if (d > (maxDepth || 8)) return;
            const r = el.getBoundingClientRect();
            if (r.width < (minSize || 8) || r.height < (minSize || 8)) return;
            out.push({
                d,
                tag: el.tagName.toLowerCase(),
                cls: cls(el).slice(0, 90),
                role: el.getAttribute('role'),
                label: label(el).slice(0, 50),
                ...box(el),
            });
            for (const c of el.children) walk(c, d + 1);
        };
        if (root) walk(root, 0);
        return out;
    }

    // ---------------------------------------------------------------- icons
    function iconName(svg) {
        let el = svg;
        for (let i = 0; i < 6 && el; i++) {
            const l = el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'));
            if (l) return l.replace(/[^\w]+/g, '_').slice(0, 48);
            el = el.parentElement;
        }
        const t = svg.querySelector('title');
        if (t) return (t.textContent || '').replace(/[^\w]+/g, '_').slice(0, 48);
        return 'icon';
    }

    function icons() {
        const seen = new Map();
        document.querySelectorAll('svg').forEach((svg) => {
            const r = svg.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) return;
            const paths = Array.from(svg.querySelectorAll('path, circle, rect, polygon, ellipse, line'))
                .map((n) => {
                    const o = {tag: n.tagName.toLowerCase()};
                    for (const a of ['d', 'cx', 'cy', 'r', 'x', 'y', 'width', 'height', 'points', 'rx', 'ry', 'x1', 'y1', 'x2', 'y2']) {
                        if (n.hasAttribute(a)) o[a] = n.getAttribute(a);
                    }
                    o.fill = n.getAttribute('fill');
                    o.stroke = n.getAttribute('stroke');
                    o.strokeWidth = n.getAttribute('stroke-width');
                    o.fillRule = n.getAttribute('fill-rule');
                    o.clipRule = n.getAttribute('clip-rule');
                    return o;
                });
            if (!paths.length) return;
            const key = JSON.stringify(paths) + svg.getAttribute('viewBox');
            if (seen.has(key)) {
                seen.get(key).uses++;
                return;
            }
            seen.set(key, {
                name: iconName(svg),
                viewBox: svg.getAttribute('viewBox') || `${r.width}x${r.height}`,
                w: Math.round(r.width),
                h: Math.round(r.height),
                color: getComputedStyle(svg).color,
                fill: svg.getAttribute('fill'),
                near: (svg.parentElement && (cls(svg.parentElement).slice(0, 60) || label(svg.parentElement))) || '',
                uses: 1,
                paths,
            });
        });
        return Array.from(seen.values());
    }

    // ---------------------------------------------------------------- text inventory
    function texts() {
        const out = [];
        document.querySelectorAll('button, [role="button"], [role="tab"], [role="menuitem"], h1, h2, h3, h4, label').forEach((el) => {
            const r = el.getBoundingClientRect();
            if (r.width < 4 || r.height < 4) return;
            const t = textOf(el, 120);
            if (!t) return;
            const cs = getComputedStyle(el);
            out.push({
                tag: el.tagName.toLowerCase(),
                role: el.getAttribute('role'),
                text: t,
                fontSize: cs.fontSize,
                fontWeight: cs.fontWeight,
                color: cs.color,
                ...box(el),
            });
        });
        return out;
    }

    return {tokens, spec, specAll, tree, icons, texts, styleOf, box, cls, label};
})();
