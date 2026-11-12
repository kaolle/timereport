import {configurePasswordVisibility} from './password-visibility.js';

const ACCOUNT_KEY = 'timereport-account';
const LOGIN_EMAIL_KEY = 'timereport-login-email';
let account;
let accessToken;
let expiresAt = 0;
let refreshPromise;
let refreshFailure;
let nextRefreshAt = 0;

function sessionState(state) {
    document.documentElement.dataset.sessionState = state;
    window.dispatchEvent(new CustomEvent('auth-session-changed', {detail: state}));
}

export function rememberedAccount() {
    try {
        const value = JSON.parse(localStorage.getItem(ACCOUNT_KEY));
        return /^[0-9a-f-]{36}$/i.test(value?.accountId) ? value : null;
    } catch {
        return null;
    }
}

async function authRequest(path, body) {
    const response = await fetch(`/api/auth/${path}`, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: {
            'Content-Type': 'application/json', 'X-Requested-With': 'timereport',
            ...(path === 'change-password' && accessToken ? {Authorization: `Bearer ${accessToken}`} : {})
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(path === 'refresh' ? 30000 : 10000)
    });
    let result;
    if (response.status !== 204) {
        try {
            result = await response.json();
        } catch { /* Generic error below. */
        }
    }
    if (!response.ok) {
        const error = new Error(result?.message || 'Begäran misslyckades. Kontrollera uppgifterna och försök igen.');
        error.status = response.status;
        throw error;
    }
    return result;
}

function acceptLogin(result) {
    if (!result?.accessToken || !result.account?.accountId) throw new Error('Ogiltigt inloggningssvar.');
    if (account && result.account.accountId !== account.accountId) {
        // Never upload one account's offline queue into a different account after a cookie change.
        accessToken = null;
        const error = new Error('Kontot ändrades. Ladda om sidan innan du fortsätter.');
        error.status = 401;
        throw error;
    }
    account = result.account;
    accessToken = result.accessToken;
    expiresAt = Date.now() + result.expiresIn * 1000;
    refreshFailure = null;
    nextRefreshAt = 0;
    localStorage.setItem(ACCOUNT_KEY, JSON.stringify(account));
    sessionState('ready');
    return account;
}

async function refreshAccess() {
    if (!refreshPromise) {
        if (refreshFailure && Date.now() < nextRefreshAt) throw refreshFailure;
        sessionState('checking');
        const refresh = async () => acceptLogin(await authRequest('refresh'));
        // One rotation at a time across tabs. Never retry a consumed refresh credential.
        refreshPromise = (navigator.locks ? navigator.locks.request('timereport-auth-refresh', refresh) : refresh())
            .catch(error => {
                accessToken = null;
                refreshFailure = error;
                const rejected = [401, 403].includes(error.status);
                nextRefreshAt = rejected ? Infinity : Date.now() + 5000;
                sessionState(rejected ? 'expired' : 'unavailable');
                throw error;
            })
            .finally(() => {
                refreshPromise = null;
            });
    }
    return refreshPromise;
}

export async function authenticatedFetch(path, {timeoutMs = 8000, ...options} = {}) {
    if (!account || rememberedAccount()?.accountId !== account.accountId) {
        const error = new Error('Logga in igen. Dina lokala tider finns kvar på rätt konto.');
        error.status = 401;
        throw error;
    }
    if (!accessToken || Date.now() >= expiresAt - 15000) await refreshAccess();
    const fetchOnce = () => fetch(path, {
        ...options, credentials: 'same-origin',
        // Start the API timeout after token renewal, including on a 401 retry.
        signal: options.signal ?? AbortSignal.timeout(timeoutMs),
        headers: {...options?.headers, Authorization: `Bearer ${accessToken}`}
    });
    let response = await fetchOnce();
    if (response.status === 401) {
        accessToken = null;
        await refreshAccess();
        response = await fetchOnce();
    }
    return response;
}

export async function logout() {
    await authRequest('logout');
    accessToken = null;
    account = null;
    localStorage.removeItem(ACCOUNT_KEY);
    location.reload();
}

export async function requireAccount() {
    const main = document.getElementById('main');
    const panel = document.getElementById('auth-panel');
    const status = document.getElementById('auth-status');
    const form = document.getElementById('auth-form');
    const mode = document.getElementById('auth-mode');
    const email = document.getElementById('auth-email');
    const password = document.getElementById('auth-password');
    const submit = document.getElementById('auth-submit');
    const retryLink = document.getElementById('auth-link-retry');
    const completeLogin = document.getElementById('auth-complete-login');
    const passwordVisibility = configurePasswordVisibility();
    const local = rememberedAccount();
    const url = new URL(location.href);
    const forceLogin = url.searchParams.get('login') === '1';
    if (forceLogin) {
        url.searchParams.delete('login');
        history.replaceState(null, '', url.pathname + url.search + url.hash);
    }
    let emailVerificationRequired = true;
    let linkToken;
    let linkMode;
    let linkEmail;
    let linkContextPending = false;
    let linkContextError;
    let initialized = false;
    let submitting = false;
    let completed = false;
    const fragment = new URLSearchParams(location.hash.slice(1));
    if (fragment.has('reset') || fragment.has('verify')) {
        mode.value = fragment.has('reset') ? 'reset-password' : 'verify-email';
        linkMode = mode.value;
        linkToken = fragment.get('reset') || fragment.get('verify');
        history.replaceState(null, '', location.pathname + location.search);
    }

    email.value = local?.email || '';
    try {
        const suggestedEmail = sessionStorage.getItem(LOGIN_EMAIL_KEY);
        sessionStorage.removeItem(LOGIN_EMAIL_KEY);
        if (suggestedEmail) email.value = suggestedEmail;
    } catch { /* The visible username remains usable when browser storage is unavailable. */ }

    function fields() {
        if (completed) return;
        const tokenMode = ['verify-email', 'reset-password'].includes(mode.value);
        const linkReady = linkToken && mode.value === linkMode && linkEmail;
        email.closest('.field').hidden = false;
        email.required = true;
        email.readOnly = tokenMode || submitting;
        email.disabled = !initialized;
        if (tokenMode) email.value = linkReady ? linkEmail : '';
        const directRegistration = mode.value === 'register' && !emailVerificationRequired;
        const needsPassword = directRegistration || !['register', 'forgot-password'].includes(mode.value);
        password.closest('.field').hidden = !needsPassword;
        password.required = needsPassword;
        password.disabled = !initialized || tokenMode && !linkReady;
        password.readOnly = submitting;
        password.autocomplete = tokenMode || directRegistration ? 'new-password' : 'current-password';
        password.minLength = tokenMode || directRegistration ? 15 : 0;
        document.querySelector('label[for="auth-password"]').textContent = tokenMode ? 'Välj nytt lösenord' : 'Lösenord';
        submit.textContent = ({
            login: 'Logga in', register: directRegistration ? 'Skapa konto' : 'Skicka registreringslänk',
            'forgot-password': 'Skicka återställningslänk', 'reset-password': 'Spara nytt lösenord',
            'verify-email': 'Spara lösenord och aktivera konto'
        })[mode.value];
        submit.disabled = !initialized || submitting || tokenMode && !linkReady;
        mode.disabled = !initialized || submitting;
        retryLink.hidden = !tokenMode || mode.value !== linkMode || !linkContextError?.retryable;
        passwordVisibility.refresh();
    }

    async function loadLinkContext() {
        if (!linkToken || linkContextPending || completed) return;
        linkContextPending = true;
        linkContextError = undefined;
        retryLink.hidden = true;
        if (mode.value === linkMode) status.textContent = 'Kontrollerar länken …';
        try {
            const result = await authRequest('link-context', {
                token: linkToken, purpose: linkMode === 'verify-email' ? 'verify' : 'reset'
            });
            if (!result?.email || typeof result.email !== 'string') throw new Error();
            linkEmail = result.email;
        } catch (error) {
            const invalid = [400, 401, 403].includes(error.status);
            linkContextError = {
                message: invalid ? 'Länken är ogiltig eller har gått ut. Begär en ny länk.'
                    : 'Länken kunde inte kontrolleras. Anslut till nätet och försök igen.',
                retryable: !invalid
            };
        } finally {
            linkContextPending = false;
            if (!completed) {
                fields();
                if (mode.value === linkMode) status.textContent = linkContextError?.message || '';
            }
        }
    }

    mode.addEventListener('change', () => {
        passwordVisibility.hideAll();
        fields();
        const tokenMode = ['verify-email', 'reset-password'].includes(mode.value);
        status.textContent = tokenMode ? mode.value !== linkMode ? 'Öppna länken från mejlet för att fortsätta.'
            : linkContextPending ? 'Kontrollerar länken …' : linkContextError?.message || '' : '';
    });
    retryLink.addEventListener('click', () => void loadLinkContext());
    fields();
    if (navigator.onLine) {
        // Registration settings must not block session recovery or access to local data.
        void (async () => {
            try {
                const response = await fetch('/api/auth/config', {
                    credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(10000)
                });
                if (response.ok) {
                    emailVerificationRequired = (await response.json()).emailVerificationRequired !== false;
                    fields();
                }
            } catch { /* Keep email verification enabled when settings cannot be fetched. */ }
        })();
    }
    if (!linkToken && !forceLogin && local) {
        // Cached account identity selects local storage only. Every API call still
        // waits for a verified access token belonging to this same account.
        account = local;
        panel.hidden = true;
        main.hidden = false;
        if (navigator.onLine) void refreshAccess().catch(() => {});
        else sessionState('unavailable');
        return account;
    }
    if (!linkToken && !forceLogin && navigator.onLine) {
        status.textContent = 'Återansluter till ditt konto…';
        try {
            const result = await refreshAccess();
            panel.hidden = true;
            main.hidden = false;
            return result;
        } catch {
            accessToken = null;
        }
        status.textContent = '';
    }
    panel.hidden = false;
    main.hidden = true;
    const offline = document.getElementById('auth-offline');
    offline.hidden = !local || Boolean(linkToken);
    return new Promise(resolve => {
        offline.addEventListener('click', () => {
            account = local;
            panel.hidden = true;
            main.hidden = false;
            resolve(account);
        }, {once: true});
        form.addEventListener('submit', async event => {
            event.preventDefault();
            if (submitting || completed) return;
            const tokenMode = ['verify-email', 'reset-password'].includes(mode.value);
            if (tokenMode && (!linkToken || mode.value !== linkMode || !linkEmail)) {
                status.textContent = 'Öppna en giltig länk från mejlet för att fortsätta.';
                return;
            }
            submitting = true;
            fields();
            status.textContent = '';
            try {
                const body = ['verify-email', 'reset-password'].includes(mode.value)
                    ? {token: linkToken, password: password.value}
                    : mode.value === 'register' && !emailVerificationRequired ? {
                            email: email.value,
                            password: password.value
                        }
                        : mode.value === 'forgot-password' || mode.value === 'register' ? {email: email.value}
                        : {email: email.value, password: password.value};
                if (['verify-email', 'reset-password'].includes(mode.value) && !linkToken) {
                    throw new Error('Öppna länken från mejlet för att fortsätta.');
                }
                const result = await authRequest(mode.value, body);
                if (mode.value === 'login' || (mode.value === 'register' && !emailVerificationRequired)) {
                    account = undefined;
                    acceptLogin(result);
                    panel.hidden = true;
                    main.hidden = false;
                    password.value = '';
                    resolve(account);
                    return;
                }
                if (tokenMode) {
                    // Remove the completed form before clearing its fields so
                    // password managers can recognize the successful submission.
                    completed = true;
                    form.remove();
                    password.value = '';
                    linkToken = undefined;
                    try { sessionStorage.setItem(LOGIN_EMAIL_KEY, linkEmail); } catch { /* Email hint only. */ }
                    const loginUrl = new URL(location.href);
                    loginUrl.searchParams.set('login', '1');
                    const target = loginUrl.pathname + loginUrl.search;
                    history.replaceState(null, '', target);
                    completeLogin.href = target;
                    completeLogin.hidden = false;
                    status.textContent = mode.value === 'verify-email'
                        ? 'Kontot är aktiverat. Du kan nu logga in.' : 'Lösenordet är ändrat. Du kan nu logga in.';
                    completeLogin.focus();
                } else {
                    status.textContent = result?.message || 'Klart. Du kan nu logga in.';
                    password.value = '';
                }
            } catch (error) {
                status.textContent = error.message || 'Servern kan inte nås.';
            } finally {
                submitting = false;
                fields();
            }
        });
        // Visible HTML may arrive before the module graph. Enable interaction only
        // after initialization has set defaults and installed the submit handler.
        status.textContent = '';
        initialized = true;
        fields();
        if (linkToken) void loadLinkContext();
    });
}

export function configureAccountTools(current) {
    document.getElementById('account-email').textContent = current.email;
    document.getElementById('reauth').addEventListener('click', () => {
        const url = new URL(location.href);
        url.searchParams.set('login', '1');
        location.assign(url);
    });
    document.getElementById('change-password-form').addEventListener('submit', async event => {
        event.preventDefault();
        const status = document.getElementById('password-status');
        try {
            if (!accessToken || Date.now() >= expiresAt - 15000) await refreshAccess();
            await authRequest('change-password', {
                currentPassword: document.getElementById('current-password').value,
                newPassword: document.getElementById('new-password').value
            });
            accessToken = null;
            localStorage.removeItem(ACCOUNT_KEY);
            location.reload();
        } catch (error) {
            status.textContent = error.message;
        }
    });
}

window.addEventListener('storage', event => {
    if (event.key === ACCOUNT_KEY && account && rememberedAccount()?.accountId !== account.accountId) {
        // A reload immediately halts old-account UI and synchronization after logout/account switching.
        accessToken = null;
        location.reload();
    }
});
