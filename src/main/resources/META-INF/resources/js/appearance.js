import {authenticatedFetch, rememberedAccount} from './auth.js';

const cacheKey = 'tidrapporten-theme';
const motionKey = 'tidrapporten-christmas-motion';
const themes = new Set(['STANDARD', 'CHRISTMAS']);

// A public visual preference; no account information or credentials are cached here.
export function configureAppearance() {
    const root = document.documentElement;
    const form = document.getElementById('appearance-form');
    const select = document.getElementById('appearance-theme');
    const save = document.getElementById('appearance-save');
    const status = document.getElementById('appearance-status');
    let revision = 0;
    let busy = false;
    let loading = false;
    let dirty = false;
    let allowed = false;
    let snowfall;
    let santas;
    const motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');
    const motionControls = document.createElement('div');
    motionControls.className = 'christmas-motion-controls';
    motionControls.hidden = true;
    const motionButton = document.createElement('button');
    motionButton.type = 'button';
    motionButton.className = 'secondary-button';
    motionButton.id = 'christmas-motion-toggle';
    const motionHint = document.createElement('span');
    motionHint.className = 'field-help';
    motionControls.append(motionButton, motionHint);
    document.querySelector('.site-header').after(motionControls);
    let motionChoice;
    try { motionChoice = localStorage.getItem(motionKey); } catch { /* Follow device preference. */ }

    function updateMotion() {
        const enabled = motionChoice === 'on' || motionChoice !== 'off' && !motionPreference.matches;
        root.dataset.christmasMotion = enabled ? 'on' : 'off';
        motionButton.textContent = enabled ? 'Pausa julanimationer' : 'Starta julanimationer';
        motionHint.textContent = !enabled && motionPreference.matches && motionChoice !== 'off'
            ? 'Enheten har minskad rörelse aktiverat. Du kan starta julanimationerna här.' : '';
    }
    motionButton.addEventListener('click', () => {
        motionChoice = root.dataset.christmasMotion === 'on' ? 'off' : 'on';
        try { localStorage.setItem(motionKey, motionChoice); } catch { /* Keep the choice for this opening. */ }
        updateMotion();
    });
    motionPreference.addEventListener('change', updateMotion);
    updateMotion();

    function decorate(theme) {
        motionControls.hidden = theme !== 'CHRISTMAS';
        if (theme === 'CHRISTMAS' && !snowfall) {
            snowfall = document.createElement('div');
            snowfall.className = 'christmas-snow';
            snowfall.setAttribute('aria-hidden', 'true');
            snowfall.inert = true;
            // Fixed, small particle count: animation uses transforms, with no timers or rendering loop.
            for (let index = 0; index < 18; index++) {
                const flake = document.createElement('span');
                flake.textContent = index % 3 === 0 ? '❄' : '❅';
                flake.style.setProperty('--snow-left', `${(index * 37) % 100}%`);
                flake.style.setProperty('--snow-size', `${28 + index % 4 * 6}px`);
                flake.style.setProperty('--snow-duration', `${24 + index % 7 * 2}s`);
                flake.style.setProperty('--snow-delay', `${-index * 2.3}s`);
                snowfall.append(flake);
            }
            document.body.append(snowfall);
        }
        if (theme === 'CHRISTMAS' && !santas) {
            santas = document.createElement('div');
            santas.className = 'christmas-santas';
            santas.setAttribute('aria-hidden', 'true');
            santas.inert = true;
            for (let index = 0; index < 2; index++) {
                const santa = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                santa.setAttribute('viewBox', '0 0 70 100');
                // Static decorative artwork; no user content is inserted into the SVG.
                santa.innerHTML = `
                    <g class="santa-leg santa-leg-left">
                        <path d="M25 64v20" stroke="#e32646" stroke-width="10" stroke-linecap="round"/>
                        <path d="M25 84h-8" stroke="#263c36" stroke-width="10" stroke-linecap="round"/>
                        <path d="M21 79h8" stroke="#fff" stroke-width="4"/>
                    </g>
                    <g class="santa-leg santa-leg-right">
                        <path d="M45 64v20" stroke="#e32646" stroke-width="10" stroke-linecap="round"/>
                        <path d="M45 84h8" stroke="#263c36" stroke-width="10" stroke-linecap="round"/>
                        <path d="M41 79h8" stroke="#fff" stroke-width="4"/>
                    </g>
                    <path d="M19 44Q35 35 51 44l5 19Q35 73 14 63Z" fill="#e32646"/>
                    <path d="M20 47 12 61M50 47l8 14" stroke="#e32646" stroke-width="9" stroke-linecap="round"/>
                    <circle cx="11" cy="62" r="5" fill="#fff"/><circle cx="59" cy="62" r="5" fill="#fff"/>
                    <path d="M20 61h30" stroke="#263c36" stroke-width="6"/>
                    <rect x="31" y="57" width="8" height="8" rx="1" fill="#f6d675"/>
                    <circle cx="35" cy="31" r="15" fill="#ffd2b2"/>
                    <path d="M19 31Q16 51 35 54 54 51 51 31L44 36 35 33 26 36Z" fill="#fff"/>
                    <circle cx="29" cy="29" r="1.5" fill="#263c36"/><circle cx="41" cy="29" r="1.5" fill="#263c36"/>
                    <ellipse cx="35" cy="35" rx="5" ry="4" fill="#f4ad94"/>
                    <path d="M20 23Q24 1 43 7l13 11-15-3 9 9Z" fill="#e32646"/>
                    <path d="M20 23h30" stroke="#fff" stroke-width="7" stroke-linecap="round"/>
                    <circle cx="56" cy="18" r="5" fill="#fff"/>`;
                santas.append(santa);
            }
            document.querySelector('.site-header').append(santas);
        }
        if (santas) santas.hidden = theme !== 'CHRISTMAS';
        if (snowfall) snowfall.hidden = theme !== 'CHRISTMAS' || document.hidden;
    }

    function apply(theme) {
        if (!themes.has(theme)) return;
        root.dataset.theme = theme.toLowerCase();
        decorate(theme);
        if (!dirty) select.value = theme;
        try { localStorage.setItem(cacheKey, theme); } catch { /* Storage may be disabled. */ }
    }

    function access() {
        allowed = root.dataset.sessionState === 'ready' && rememberedAccount()?.roles?.includes('admin') === true;
        save.disabled = busy || !allowed || !navigator.onLine;
        select.disabled = busy || !allowed;
    }

    async function refresh() {
        if (!navigator.onLine || loading || busy) return;
        loading = true;
        const started = revision;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5000);
        try {
            const response = await fetch('/api/appearance', {cache: 'no-store', signal: controller.signal});
            if (response.ok) {
                const value = await response.json();
                if (started === revision) apply(value.theme);
            }
        } catch { /* Keep the last theme while offline or during a cold start. */ }
        finally { clearTimeout(timeout); loading = false; }
    }

    try { apply(localStorage.getItem(cacheKey)); } catch { /* Use standard appearance. */ }
    select.addEventListener('change', () => { dirty = true; status.textContent = ''; });
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (busy || !allowed || !navigator.onLine) return;
        busy = true;
        revision++;
        access();
        status.textContent = 'Sparar tema …';
        try {
            const response = await authenticatedFetch('/api/admin/appearance', {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({theme: select.value}), timeoutMs: 15000
            });
            const result = await response.json().catch(() => ({}));
            if (!response.ok || !themes.has(result.theme)) {
                throw new Error(response.status === 403 ? 'Administratörsbehörighet krävs. Logga in igen.'
                    : result.message || 'Temat kunde inte sparas. Försök igen.');
            }
            dirty = false;
            apply(result.theme);
            status.textContent = 'Temat är sparat och gäller alla användare. Öppna appar hämtar det när de blir aktiva igen.';
        } catch (error) { status.textContent = error.message || 'Temat kunde inte sparas.'; }
        finally { busy = false; access(); }
    });
    window.addEventListener('auth-session-changed', access);
    window.addEventListener('online', () => { access(); void refresh(); });
    window.addEventListener('offline', access);
    document.addEventListener('visibilitychange', () => {
        if (snowfall) snowfall.hidden = root.dataset.theme !== 'christmas' || document.hidden;
        if (!document.hidden) void refresh();
    });
    window.addEventListener('storage', event => {
        if (event.key === motionKey) { motionChoice = event.newValue; updateMotion(); }
        if (event.key === cacheKey) { revision++; apply(event.newValue || 'STANDARD'); }
    });
    access();
    void refresh();
}
