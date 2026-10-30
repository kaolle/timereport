const $ = id => document.getElementById(id);

function message(id, text) {
    $(id).textContent = text;
    $(id).hidden = !text;
}

export function renderConnectionStatus({onlineStatus, codesBusy}) {
    const session = document.documentElement.dataset.sessionState;
    const localOnly = session && session !== 'ready';
    const offline = !navigator.onLine || onlineStatus === false || localOnly;
    $('connection').classList.toggle('offline', offline);
    $('connection').textContent = !navigator.onLine ? 'Utan nät · sparar lokalt'
        : session === 'checking' ? 'Återansluter · sparar lokalt'
            : session === 'expired' ? 'Logga in för att synka'
                : session === 'unavailable' ? 'Sparar lokalt · väntar på servern'
                    : onlineStatus === false ? 'Servern kan inte nås' : onlineStatus ? 'Ansluten' : 'Nät tillgängligt';
    if ($('session-status')) {
        $('session-status').hidden = !localOnly;
        $('session-login').hidden = session !== 'expired';
        $('session-message').textContent = session === 'expired'
            ? 'Logga in igen för att synka. Du kan fortsätta registrera tid och lägga till kundkod i tidsformuläret lokalt.'
            : 'Du kan registrera tid och lägga till kundkod i tidsformuläret. Ändringarna sparas lokalt och synkas när anslutningen är klar.';
    }
    $('add-code').disabled = !navigator.onLine || codesBusy;
}

export function renderSyncSummary(data, onlineStatus) {
    const pending = data.outbox.length + data.vacationOutbox.length;
    const problem = data.entries.find(entry => entry.syncError);
    message('sync-summary', problem?.syncError || (pending
        ? `${pending} ${pending === 1 ? 'ändring sparad' : 'ändringar sparade'} lokalt · ${!navigator.onLine || onlineStatus === false ? 'väntar på anslutning' : 'synkas automatiskt'}.`
        : ''));
}

