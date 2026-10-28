const ACCOUNT_KEY = 'timereport-account';
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
    const local = rememberedAccount();
    const url = new URL(location.href);
    const forceLogin = url.searchParams.get('login') === '1';
    if (forceLogin) {
        url.searchParams.delete('login');
        history.replaceState(null, '', url.pathname + url.search + url.hash);
    }
    let emailVerificationRequired = true;
    let linkToken;
    const fragment = new URLSearchParams(location.hash.slice(1));
    if (fragment.has('reset') || fragment.has('verify')) {
        mode.value = fragment.has('reset') ? 'reset-password' : 'verify-email';
        linkToken = fragment.get('reset') || fragment.get('verify');
        history.replaceState(null, '', location.pathname + location.search);
    }

    function fields() {
        const tokenMode = ['verify-email', 'reset-password'].includes(mode.value);
        email.closest('.field').hidden = tokenMode;
        email.required = !tokenMode;
        const directRegistration = mode.value === 'register' && !emailVerificationRequired;
        const needsPassword = directRegistration || !['register', 'forgot-password'].includes(mode.value);
        password.closest('.field').hidden = !needsPassword;
        password.required = needsPassword;
        password.autocomplete = tokenMode || directRegistration ? 'new-password' : 'current-password';
        password.minLength = tokenMode || directRegistration ? 15 : 0;
        document.querySelector('label[for="auth-password"]').textContent = tokenMode ? 'Välj nytt lösenord' : 'Lösenord';
        document.getElementById('auth-submit').textContent = ({
            login: 'Logga in', register: directRegistration ? 'Skapa konto' : 'Skicka registreringslänk',
            'forgot-password': 'Skicka återställningslänk', 'reset-password': 'Spara nytt lösenord',
            'verify-email': 'Spara lösenord och aktivera konto'
        })[mode.value];
    }

    mode.addEventListener('change', fields);
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
    email.value = local?.email || '';
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
            const button = document.getElementById('auth-submit');
            button.disabled = true;
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
                status.textContent = result?.message || 'Klart. Du kan nu logga in.';
                password.value = '';
                if (['verify-email', 'reset-password'].includes(mode.value)) {
                    mode.value = 'login';
                    linkToken = undefined;
                    fields();
                }
            } catch (error) {
                status.textContent = error.message || 'Servern kan inte nås.';
            } finally {
                button.disabled = false;
            }
        });
        // Visible HTML may arrive before the module graph. Enable interaction only
        // after initialization has set defaults and installed the submit handler.
        status.textContent = '';
        for (const control of [mode, email, password, document.getElementById('auth-submit')]) {
            control.disabled = false;
        }
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
