const ACCOUNT_KEY = 'timereport-account';
let account;
let accessToken;
let expiresAt = 0;
let refreshPromise;

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
        signal: AbortSignal.timeout(10000)
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
    localStorage.setItem(ACCOUNT_KEY, JSON.stringify(account));
    return account;
}

async function refreshAccess() {
    if (!refreshPromise) {
        const refresh = async () => acceptLogin(await authRequest('refresh'));
        // One rotation at a time across tabs. Never retry a consumed refresh credential.
        refreshPromise = (navigator.locks ? navigator.locks.request('timereport-auth-refresh', refresh) : refresh())
            .finally(() => {
                refreshPromise = null;
            });
    }
    return refreshPromise;
}

export async function authenticatedFetch(path, options) {
    if (!account || rememberedAccount()?.accountId !== account.accountId) {
        const error = new Error('Logga in igen. Dina lokala tider finns kvar på rätt konto.');
        error.status = 401;
        throw error;
    }
    if (!accessToken || Date.now() >= expiresAt - 15000) await refreshAccess();
    const fetchOnce = () => fetch(path, {
        ...options, credentials: 'same-origin',
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
    let emailVerificationRequired = true;
    if (navigator.onLine) {
        try {
            const response = await fetch('/api/auth/config', {credentials: 'same-origin', cache: 'no-store'});
            if (response.ok) emailVerificationRequired = (await response.json()).emailVerificationRequired !== false;
        } catch { /* Keep the safe default when offline or the config endpoint is unavailable. */
        }
    }
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
    if (!linkToken && navigator.onLine) {
        try {
            const result = await refreshAccess();
            panel.hidden = true;
            main.hidden = false;
            return result;
        } catch {
            accessToken = null;
        }
    }
    if (!linkToken && !navigator.onLine && local) {
        account = local;
        panel.hidden = true;
        main.hidden = false;
        return account;
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
    });
}

export function configureAccountTools(current) {
    document.getElementById('account-email').textContent = current.email;
    document.getElementById('reauth').addEventListener('click', () => location.reload());
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
