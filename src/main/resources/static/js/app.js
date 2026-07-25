/* Planning Poker SPA client: REST + STOMP over SockJS.
 *
 * Story text and participant names are end-to-end encrypted here, with a
 * room key that never leaves the browser: it is generated on session create,
 * shared through the fragment of the invite link (fragments are not sent to
 * servers), and held in sessionStorage for the tab's lifetime. The server
 * stores and echoes those fields as opaque ciphertext, so neither a database
 * admin nor the operator running the app can read them.
 *
 * Card values are deliberately NOT encrypted here — the server has to compare
 * them against the deck, compute the statistics everyone sees, and decide
 * auto-reveal. They are encrypted at rest with a server-held key instead.
 * See docs/PRIVACY.md.
 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);

    // ---------- client state ----------
    let auth = loadAuth();            // { token, code, participantId, roomKey }
    let snapshot = null;              // latest SessionSnapshot (decrypted)
    let stomp = null;
    let reconnectDelay = 1000;
    let intentionalDisconnect = false;

    function loadAuth() {
        try {
            return JSON.parse(sessionStorage.getItem('pp-auth')) || null;
        } catch (e) {
            return null;
        }
    }

    function saveAuth(next) {
        auth = next;
        if (next) {
            sessionStorage.setItem('pp-auth', JSON.stringify(next));
        } else {
            sessionStorage.removeItem('pp-auth');
        }
    }

    // ---------- end-to-end encryption ----------
    const ENVELOPE_PREFIX = 'e1.';
    const LOCKED = '🔒 encrypted';

    const room = {
        raw: null,   // base64url key material, shared out-of-band via the link
        key: null    // imported CryptoKey
    };

    function cryptoAvailable() {
        return typeof crypto !== 'undefined' && crypto.subtle;
    }

    function b64uEncode(bytes) {
        let s = '';
        bytes.forEach(b => { s += String.fromCharCode(b); });
        return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    function b64uDecode(text) {
        const padded = text.replace(/-/g, '+').replace(/_/g, '/');
        const raw = atob(padded + '==='.slice((padded.length + 3) % 4));
        return Uint8Array.from(raw, ch => ch.charCodeAt(0));
    }

    async function generateRoomKey() {
        const key = await crypto.subtle.generateKey({name: 'AES-GCM', length: 256},
            true, ['encrypt', 'decrypt']);
        const raw = new Uint8Array(await crypto.subtle.exportKey('raw', key));
        room.key = key;
        room.raw = b64uEncode(raw);
        return room.raw;
    }

    async function useRoomKey(raw) {
        room.key = await crypto.subtle.importKey('raw', b64uDecode(raw),
            {name: 'AES-GCM'}, true, ['encrypt', 'decrypt']);
        room.raw = raw;
    }

    /** Encrypts to "e1." + base64url(iv || ciphertext); null and '' pass through. */
    async function enc(plaintext) {
        if (plaintext === null || plaintext === undefined || plaintext === '') {
            return plaintext === '' ? '' : null;
        }
        if (!room.key) throw new Error('No room key — cannot encrypt');
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const ct = new Uint8Array(await crypto.subtle.encrypt({name: 'AES-GCM', iv},
            room.key, new TextEncoder().encode(plaintext)));
        const envelope = new Uint8Array(iv.length + ct.length);
        envelope.set(iv, 0);
        envelope.set(ct, iv.length);
        return ENVELOPE_PREFIX + b64uEncode(envelope);
    }

    /**
     * Decrypts a stored value. A wrong or missing key yields a locked marker
     * rather than an exception, so a participant who followed a bare code sees
     * a usable session with unreadable text instead of a broken page.
     */
    async function dec(stored) {
        if (stored === null || stored === undefined || stored === '') return stored;
        if (typeof stored !== 'string' || !stored.startsWith(ENVELOPE_PREFIX)) return stored;
        if (!room.key) return LOCKED;
        try {
            const envelope = b64uDecode(stored.slice(ENVELOPE_PREFIX.length));
            const plain = await crypto.subtle.decrypt(
                {name: 'AES-GCM', iv: envelope.slice(0, 12)}, room.key, envelope.slice(12));
            return new TextDecoder().decode(plain);
        } catch (e) {
            return LOCKED;
        }
    }

    /** Decrypts every end-to-end encrypted field of a snapshot in place. */
    async function decryptSnapshot(s) {
        if (!s) return s;
        for (const p of s.participants || []) {
            p.displayName = await dec(p.displayName);
        }
        for (const st of s.stories || []) {
            await decryptStory(st);
        }
        // the active story is a separate object in the payload, not a reference
        if (s.activeStory) await decryptStory(s.activeStory);
        for (const v of (s.activeRound && s.activeRound.votes) || []) {
            v.displayName = await dec(v.displayName);
        }
        return s;
    }

    async function decryptStory(st) {
        st.title = await dec(st.title);
        st.description = await dec(st.description);
        st.referenceUrl = await dec(st.referenceUrl);
        return st;
    }

    function shareLink() {
        // the key rides in the fragment, which browsers never send to the server
        return location.origin + '/?join=' + auth.code + '#k=' + auth.roomKey;
    }

    // ---------- helpers ----------
    function toast(message, ms) {
        const el = $('toast');
        el.textContent = message;
        el.hidden = false;
        clearTimeout(el._t);
        el._t = setTimeout(() => { el.hidden = true; }, ms || 3500);
    }

    async function api(path, options) {
        const opts = Object.assign({headers: {}}, options);
        opts.headers['Content-Type'] = 'application/json';
        if (auth && auth.token) {
            opts.headers['Authorization'] = 'Bearer ' + auth.token;
        }
        const res = await fetch(path, opts);
        if (!res.ok) {
            let message = 'Request failed (' + res.status + ')';
            try {
                const body = await res.json();
                if (body && body.message) message = body.message;
            } catch (e) { /* ignore */ }
            throw new Error(message);
        }
        const text = await res.text();
        return text ? JSON.parse(text) : null;
    }

    function me() {
        if (!snapshot || !auth) return null;
        return snapshot.participants.find(p => p.id === auth.participantId) || null;
    }

    function isFacilitator() {
        return snapshot && auth && snapshot.facilitatorParticipantId === auth.participantId;
    }

    function myRole() {
        const m = me();
        return m ? m.role : null;
    }

    // ---------- view switching ----------
    function showHome() {
        $('view-home').hidden = false;
        $('view-session').hidden = true;
        document.querySelector('#view-home').style.display = '';
    }

    function showSession() {
        $('view-home').hidden = true;
        $('view-session').hidden = false;
    }

    // ---------- home: decks ----------
    async function loadDecks() {
        try {
            const decks = await api('/api/decks');
            const select = $('create-deck');
            select.innerHTML = '';
            decks.forEach(d => {
                const opt = document.createElement('option');
                opt.value = d.id;
                opt.textContent = d.name + ' (' + d.values.slice(0, 6).join(', ') + '…)';
                select.appendChild(opt);
            });
            const custom = document.createElement('option');
            custom.value = 'custom';
            custom.textContent = 'Custom deck…';
            select.appendChild(custom);
        } catch (e) {
            toast('Could not load decks: ' + e.message);
        }
    }

    $('create-deck').addEventListener('change', () => {
        $('custom-deck-row').hidden = $('create-deck').value !== 'custom';
    });

    // ---------- home: create ----------
    $('create-form').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        if (!cryptoAvailable()) {
            toast('This browser cannot encrypt (needs HTTPS or localhost)', 8000);
            return;
        }
        const deckValue = $('create-deck').value;
        try {
            // a fresh room key per session, generated here and never sent
            const roomKey = await generateRoomKey();
            const body = {
                name: $('create-session-name').value.trim(),
                facilitatorName: await enc($('create-your-name').value.trim()),
                autoReveal: $('opt-auto-reveal').checked,
                allowObservers: $('opt-allow-observers').checked,
                allowVoteChange: $('opt-allow-vote-change').checked
            };
            if (deckValue === 'custom') {
                body.customDeckValues = $('custom-deck-values').value.split(',')
                    .map(s => s.trim()).filter(Boolean);
                body.customDeckName = 'Custom';
            } else {
                body.deckId = deckValue;
            }
            const res = await api('/api/sessions', {method: 'POST', body: JSON.stringify(body)});
            saveAuth({
                token: res.token, code: res.session.code,
                participantId: res.participant.id, roomKey: roomKey
            });
            snapshot = await decryptSnapshot(res.session);
            enterSession();
            toast('Session created — use “Copy invite link” to invite people, '
                + 'the code alone cannot decrypt this session', 9000);
        } catch (e) {
            toast(e.message);
        }
    });

    // ---------- home: join ----------
    $('join-form').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        if (!cryptoAvailable()) {
            toast('This browser cannot decrypt (needs HTTPS or localhost)', 8000);
            return;
        }
        const code = $('join-code').value.trim().toUpperCase();
        // the key came from the invite link's fragment, or is typed in
        const typedKey = $('join-room-key').value.trim();
        try {
            if (!room.key) {
                if (!typedKey) {
                    toast('This session is end-to-end encrypted — open the invite link, '
                        + 'or paste the room key the facilitator shared', 9000);
                    $('join-room-key').focus();
                    return;
                }
                await useRoomKey(typedKey);
            }
        } catch (e) {
            toast('That room key is not valid');
            return;
        }
        try {
            const body = {
                displayName: await enc($('join-your-name').value.trim()),
                role: $('join-as-observer').checked ? 'OBSERVER' : 'VOTER'
            };
            const res = await api('/api/sessions/' + encodeURIComponent(code) + '/participants',
                {method: 'POST', body: JSON.stringify(body)});
            saveAuth({
                token: res.token, code: res.session.code,
                participantId: res.participant.id, roomKey: room.raw
            });
            snapshot = await decryptSnapshot(res.session);
            enterSession();
        } catch (e) {
            toast(e.message);
        }
    });

    // ---------- session lifecycle ----------
    function enterSession() {
        showSession();
        render();
        connectStomp();
    }

    function leaveSession(silent) {
        intentionalDisconnect = true;
        if (stomp) {
            try { stomp.disconnect(function () {}); } catch (e) { /* ignore */ }
            stomp = null;
        }
        saveAuth(null);
        snapshot = null;
        showHome();
        if (!silent) toast('You left the session');
    }

    $('btn-leave').addEventListener('click', async () => {
        if (auth) {
            try {
                await api('/api/sessions/' + auth.code + '/participants/' + auth.participantId,
                    {method: 'DELETE'});
            } catch (e) { /* leaving anyway */ }
        }
        leaveSession();
    });

    $('btn-close-session').addEventListener('click', async () => {
        if (!confirm('Close this session for everyone?')) return;
        try {
            await api('/api/sessions/' + auth.code + '/close', {method: 'POST'});
        } catch (e) {
            toast(e.message);
        }
    });

    $('btn-copy-link').addEventListener('click', () => {
        const url = shareLink();
        navigator.clipboard.writeText(url).then(
            () => toast('Invite link copied — it carries the decryption key, share it privately', 6000),
            () => toast(url, 12000));
    });

    $('btn-show-room-key').addEventListener('click', () => {
        const box = $('room-key-box');
        box.hidden = !box.hidden;
        if (!box.hidden) {
            $('room-key-value').value = auth.roomKey;
            $('room-key-value').select();
        }
    });

    // ---------- STOMP ----------
    function connectStomp() {
        if (!auth) return;
        intentionalDisconnect = false;
        const sock = new SockJS('/ws');
        stomp = Stomp.over(sock);
        stomp.debug = null;
        stomp.heartbeat.outgoing = 10000;
        stomp.heartbeat.incoming = 10000;
        stomp.connect({Authorization: 'Bearer ' + auth.token},
            function onConnect() {
                reconnectDelay = 1000;
                setConnIndicator(true);
                // subscribe to the user queues first, then the topic:
                // subscribing to the topic triggers a server-side snapshot push (RT-3)
                stomp.subscribe('/user/queue/snapshot', (frame) => {
                    const incoming = JSON.parse(frame.body);
                    eventChain = eventChain.then(async () => {
                        snapshot = await decryptSnapshot(incoming);
                        render();
                    });
                });
                stomp.subscribe('/user/queue/errors', (frame) => {
                    const err = JSON.parse(frame.body);
                    toast(err.message || 'Action failed');
                });
                stomp.subscribe('/topic/sessions/' + auth.code, (frame) => {
                    handleEvent(JSON.parse(frame.body));
                });
            },
            function onError() {
                setConnIndicator(false);
                if (intentionalDisconnect || !auth) return;
                setTimeout(connectStomp, reconnectDelay);
                reconnectDelay = Math.min(reconnectDelay * 2, 15000);
            });
        sock.onclose = function () {
            setConnIndicator(false);
            if (!intentionalDisconnect && auth) {
                setTimeout(connectStomp, reconnectDelay);
                reconnectDelay = Math.min(reconnectDelay * 2, 15000);
            }
        };
    }

    function setConnIndicator(connected) {
        const el = $('conn-indicator');
        el.classList.toggle('connected', connected);
        el.classList.toggle('disconnected', !connected);
        el.setAttribute('aria-label', connected ? 'Connected' : 'Disconnected');
    }

    async function refetchSnapshot() {
        if (!auth) return;
        try {
            snapshot = await decryptSnapshot(await api('/api/sessions/' + auth.code));
            render();
        } catch (e) {
            toast(e.message);
        }
    }

    // ---------- server events ----------
    // decryption is async, so events are drained through a promise chain to
    // guarantee they apply in arrival order
    let eventChain = Promise.resolve();

    function handleEvent(event) {
        eventChain = eventChain
            .then(() => applyEvent(event))
            .catch(e => console.error('event handling failed', e));
    }

    async function applyEvent(event) {
        if (!snapshot) { await refetchSnapshot(); return; }
        const p = await decryptEventPayload(event.type, event.payload);
        switch (event.type) {
            case 'PARTICIPANT_JOINED':
                if (!snapshot.participants.some(x => x.id === p.id)) {
                    snapshot.participants.push(p);
                }
                render();
                break;
            case 'PARTICIPANT_LEFT':
                snapshot.participants = snapshot.participants.filter(x => x.id !== p.id);
                if (auth && p.id === auth.participantId) {
                    toast('You were removed from the session');
                    leaveSession(true);
                    return;
                }
                render();
                break;
            case 'PARTICIPANT_UPDATED':
            case 'CONNECTION_CHANGED': {
                const idx = snapshot.participants.findIndex(x => x.id === p.id);
                if (idx >= 0) snapshot.participants[idx] = p; else snapshot.participants.push(p);
                render();
                break;
            }
            case 'FACILITATOR_CHANGED':
                snapshot.facilitatorParticipantId = p;
                render();
                break;
            case 'VOTE_STATUS': {
                const voter = snapshot.participants.find(x => x.id === p.participantId);
                if (voter) voter.hasVoted = p.hasVoted;
                render();
                break;
            }
            case 'ROUND_REVEALED':
                snapshot.activeRound = p;
                render();
                break;
            case 'ROUND_RESET':
            case 'STORY_SELECTED':
            case 'STORIES_CHANGED':
            case 'STORY_ESTIMATED':
                await refetchSnapshot();
                break;
            case 'SESSION_CLOSED':
                toast('The session was closed');
                leaveSession(true);
                break;
        }
    }

    /** Decrypts the encrypted fields carried by each event type. */
    async function decryptEventPayload(type, payload) {
        if (!payload) return payload;
        switch (type) {
            case 'PARTICIPANT_JOINED':
            case 'PARTICIPANT_LEFT':
            case 'PARTICIPANT_UPDATED':
            case 'CONNECTION_CHANGED':
                return Object.assign({}, payload, {displayName: await dec(payload.displayName)});
            case 'STORY_SELECTED':
            case 'STORIES_CHANGED':
            case 'STORY_ESTIMATED':
            case 'ROUND_RESET':
                // these trigger a full refetch, so the payload is not applied
                return payload;
            case 'ROUND_REVEALED': {
                const votes = [];
                for (const v of payload.votes || []) {
                    votes.push(Object.assign({}, v, {displayName: await dec(v.displayName)}));
                }
                return Object.assign({}, payload, {votes: votes});
            }
            default:
                return payload;
        }
    }

    // ---------- rendering ----------
    let selectedCard = null;

    function render() {
        if (!snapshot) return;
        $('session-name').textContent = snapshot.name;
        $('session-code-badge').textContent = snapshot.code;
        const m = me();
        $('my-identity').textContent = m ? m.displayName + ' · ' + roleLabel(m.role) : '';
        $('btn-close-session').hidden = !isFacilitator();
        renderParticipants();
        renderActiveStory();
        renderCards();
        renderResults();
        renderStories();
    }

    function roleLabel(role) {
        return role === 'FACILITATOR' ? 'Facilitator' : role === 'OBSERVER' ? 'Observer' : 'Voter';
    }

    function renderParticipants() {
        const ul = $('participant-list');
        ul.innerHTML = '';
        const roundOpen = snapshot.activeRound && snapshot.activeRound.state === 'OPEN';
        snapshot.participants.forEach(p => {
            const li = document.createElement('li');
            if (p.connectionStatus === 'DISCONNECTED') li.classList.add('p-offline');

            const status = document.createElement('span');
            status.className = 'p-status';
            if (p.role === 'OBSERVER') {
                status.textContent = '👁';
                status.title = 'Observer';
            } else if (roundOpen) {
                status.textContent = p.hasVoted ? '✅' : '⏳';
                status.title = p.hasVoted ? 'Voted' : 'Not voted yet';
            } else {
                status.textContent = '·';
            }

            const name = document.createElement('span');
            name.className = 'p-name';
            name.textContent = p.displayName + (p.id === snapshot.facilitatorParticipantId ? ' ★' : '');
            name.title = p.displayName;

            const role = document.createElement('span');
            role.className = 'p-role';
            role.textContent = roleLabel(p.role);

            li.appendChild(status);
            li.appendChild(name);
            li.appendChild(role);

            if (isFacilitator() && p.id !== auth.participantId) {
                const actions = document.createElement('span');
                actions.className = 'p-actions';
                actions.appendChild(actionButton('Kick', async () => {
                    try {
                        await api('/api/sessions/' + auth.code + '/participants/' + p.id, {method: 'DELETE'});
                    } catch (e) { toast(e.message); }
                }, 'Remove ' + p.displayName + ' from the session'));
                actions.appendChild(actionButton(p.role === 'OBSERVER' ? '→Voter' : '→Obs', async () => {
                    const newRole = p.role === 'OBSERVER' ? 'VOTER' : 'OBSERVER';
                    try {
                        await api('/api/sessions/' + auth.code + '/participants/' + p.id + '/role',
                            {method: 'PUT', body: JSON.stringify({role: newRole})});
                    } catch (e) { toast(e.message); }
                }, 'Make ' + p.displayName + (p.role === 'OBSERVER' ? ' a voter' : ' an observer')));
                actions.appendChild(actionButton('★', async () => {
                    if (!confirm('Make ' + p.displayName + ' the facilitator?')) return;
                    try {
                        await api('/api/sessions/' + auth.code + '/participants/' + p.id + '/role',
                            {method: 'PUT', body: JSON.stringify({role: 'FACILITATOR'})});
                    } catch (e) { toast(e.message); }
                }, 'Make ' + p.displayName + ' the facilitator'));
                li.appendChild(actions);
            }
            ul.appendChild(li);
        });
    }

    function actionButton(label, onClick, accessibleName) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'btn small';
        btn.textContent = label;
        if (accessibleName) {
            // icon-only buttons need a text alternative for screen readers
            btn.title = accessibleName;
            btn.setAttribute('aria-label', accessibleName);
        }
        btn.addEventListener('click', onClick);
        return btn;
    }

    function renderActiveStory() {
        const story = snapshot.activeStory;
        $('no-active-story').hidden = !!story;
        $('active-story').hidden = !story;
        if (story) {
            $('active-story-title').textContent = story.title;
            // every participant — voters and observers alike — sees the description
            // of the story being estimated, with author line breaks preserved
            const desc = $('active-story-desc');
            desc.textContent = story.description || '';
            desc.hidden = !story.description;
            const link = $('active-story-link');
            if (story.referenceUrl) {
                link.href = story.referenceUrl;
                link.hidden = false;
            } else {
                link.hidden = true;
            }
        }
    }

    function renderCards() {
        const grid = $('card-grid');
        grid.innerHTML = '';
        const roundOpen = snapshot.activeRound && snapshot.activeRound.state === 'OPEN';
        const canVote = roundOpen && myRole() === 'VOTER';
        if (!roundOpen) selectedCard = null;

        snapshot.deck.values.forEach(value => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'card';
            btn.textContent = value;
            btn.disabled = !canVote;
            btn.setAttribute('aria-pressed', value === selectedCard ? 'true' : 'false');
            if (value === selectedCard) btn.classList.add('selected');
            btn.addEventListener('click', () => {
                if (!stomp || !stomp.connected) { toast('Not connected'); return; }
                selectedCard = value;
                stomp.send('/app/sessions/' + auth.code + '/vote', {},
                    JSON.stringify({cardValue: value}));
                render();
            });
            grid.appendChild(btn);
        });

        const revealed = snapshot.activeRound && snapshot.activeRound.state === 'REVEALED';
        const controls = $('facilitator-round-controls');
        // after reveal the facilitator can still reset for a re-vote (FR-22)
        controls.hidden = !(isFacilitator() && (roundOpen || revealed));
        $('btn-reveal').hidden = !roundOpen;
    }

    function renderResults() {
        const box = $('results');
        const round = snapshot.activeRound;
        const revealed = round && round.state === 'REVEALED';
        box.hidden = !revealed;
        if (!revealed) return;

        const ul = $('vote-list');
        ul.innerHTML = '';
        (round.votes || []).forEach(v => {
            const li = document.createElement('li');
            const name = document.createElement('span');
            name.textContent = v.displayName;
            const value = document.createElement('span');
            value.className = 'vote-value';
            value.textContent = v.cardValue;
            li.appendChild(name);
            li.appendChild(value);
            ul.appendChild(li);
        });

        const stats = round.stats;
        $('consensus-badge').hidden = !(stats && stats.consensus);
        const statsBox = $('stats');
        statsBox.innerHTML = '';
        if (stats) {
            const entries = [
                ['Votes', stats.totalVotes],
                ['Min', stats.min], ['Max', stats.max],
                ['Average', stats.average], ['Median', stats.median]
            ];
            entries.forEach(([label, val]) => {
                if (val === null || val === undefined) return;
                const div = document.createElement('div');
                div.className = 'stat';
                const b = document.createElement('b');
                b.textContent = val;
                div.appendChild(document.createTextNode(label));
                div.appendChild(b);
                statsBox.appendChild(div);
            });
            const hist = $('histogram');
            hist.innerHTML = '';
            const dist = stats.distribution || {};
            const maxCount = Math.max(1, ...Object.values(dist));
            Object.keys(dist).forEach(card => {
                const row = document.createElement('div');
                row.className = 'hist-row';
                const label = document.createElement('span');
                label.className = 'hist-label';
                label.textContent = card;
                const bar = document.createElement('span');
                bar.className = 'hist-bar';
                bar.style.width = (dist[card] / maxCount * 60) + '%';
                const count = document.createElement('span');
                count.className = 'hist-count';
                count.textContent = dist[card];
                row.appendChild(label);
                row.appendChild(bar);
                row.appendChild(count);
                hist.appendChild(row);
            });
        }

        const form = $('estimate-form');
        form.hidden = !isFacilitator();
        if (isFacilitator() && stats && !$('estimate-input').value) {
            $('estimate-input').value = stats.consensus && round.votes.length
                ? round.votes[0].cardValue
                : (stats.median !== null && stats.median !== undefined ? stats.median : '');
        }
    }

    $('estimate-form').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const story = snapshot.activeStory;
        if (!story) return;
        try {
            await api('/api/sessions/' + auth.code + '/stories/' + story.id + '/estimate',
                {method: 'POST', body: JSON.stringify({value: $('estimate-input').value.trim()})});
            $('estimate-input').value = '';
        } catch (e) {
            toast(e.message);
        }
    });

    $('btn-reveal').addEventListener('click', () => {
        stomp.send('/app/sessions/' + auth.code + '/reveal', {}, '{}');
    });

    $('btn-reset').addEventListener('click', () => {
        selectedCard = null;
        stomp.send('/app/sessions/' + auth.code + '/reset', {}, '{}');
    });

    // ---------- stories ----------
    function renderStories() {
        const ul = $('story-list');
        ul.innerHTML = '';
        const facilitator = isFacilitator();
        $('story-admin').hidden = !facilitator;

        if (!snapshot.stories.length) {
            const li = document.createElement('li');
            li.className = 'muted';
            li.textContent = 'No stories yet.';
            ul.appendChild(li);
        }

        snapshot.stories.forEach((s, index) => {
            const li = document.createElement('li');

            const main = document.createElement('div');
            main.className = 'story-main';
            const title = document.createElement('span');
            title.className = 'story-title';
            title.textContent = s.title;
            title.title = s.title;
            main.appendChild(title);
            if (s.description) {
                // clamped preview so everyone can see a story has details
                // before it becomes the active story
                const preview = document.createElement('span');
                preview.className = 'story-desc-preview';
                preview.textContent = s.description;
                preview.title = s.description;
                main.appendChild(preview);
            }
            li.appendChild(main);

            const badge = document.createElement('span');
            badge.className = 'story-badge ' + s.status.toLowerCase();
            badge.textContent = s.status === 'ESTIMATED' && s.finalEstimate
                ? s.finalEstimate : s.status.toLowerCase();
            li.appendChild(badge);

            if (facilitator) {
                const actions = document.createElement('span');
                actions.className = 'story-actions';
                if (s.status !== 'VOTING') {
                    actions.appendChild(actionButton('▶', () => {
                        selectedCard = null;
                        stomp.send('/app/sessions/' + auth.code + '/story/select', {},
                            JSON.stringify({storyId: s.id}));
                    }, 'Start voting on "' + s.title + '"'));
                }
                if (index > 0) {
                    actions.appendChild(actionButton('↑', () => moveStory(s, index - 1), 'Move up'));
                }
                if (index < snapshot.stories.length - 1) {
                    actions.appendChild(actionButton('↓', () => moveStory(s, index + 1), 'Move down'));
                }
                actions.appendChild(actionButton('✎', () => startEdit(s),
                        'Edit title, description and reference'));
                actions.appendChild(actionButton('✕', async () => {
                    if (!confirm('Delete "' + s.title + '"?')) return;
                    try {
                        await api('/api/sessions/' + auth.code + '/stories/' + s.id, {method: 'DELETE'});
                    } catch (e) { toast(e.message); }
                }, 'Delete story'));
                li.appendChild(actions);
            }
            ul.appendChild(li);
        });
    }

    async function moveStory(story, newIndex) {
        try {
            await api('/api/sessions/' + auth.code + '/stories/' + story.id,
                {method: 'PUT', body: JSON.stringify({orderIndex: newIndex})});
        } catch (e) {
            toast(e.message);
        }
    }

    // the story form doubles as an editor, so a description can be added to a
    // story that already exists; null while adding, story id while editing
    let editingStoryId = null;

    function startEdit(story) {
        editingStoryId = story.id;
        $('story-title').value = story.title;
        $('story-description').value = story.description || '';
        $('story-url').value = story.referenceUrl || '';
        $('btn-save-story').textContent = 'Save story';
        $('btn-cancel-edit').hidden = false;
        $('story-title').focus();
    }

    function resetStoryForm() {
        editingStoryId = null;
        $('story-title').value = '';
        $('story-description').value = '';
        $('story-url').value = '';
        $('btn-save-story').textContent = 'Add story';
        $('btn-cancel-edit').hidden = true;
    }

    $('btn-cancel-edit').addEventListener('click', resetStoryForm);

    $('add-story-form').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        // encrypted before it leaves the browser; the server stores blobs
        const body = {
            title: await enc($('story-title').value.trim()),
            description: await enc($('story-description').value.trim()),
            referenceUrl: await enc($('story-url').value.trim())
        };
        const editing = editingStoryId;
        try {
            if (editing) {
                await api('/api/sessions/' + auth.code + '/stories/' + editing,
                    {method: 'PUT', body: JSON.stringify(body)});
            } else {
                await api('/api/sessions/' + auth.code + '/stories',
                    {method: 'POST', body: JSON.stringify(body)});
            }
            resetStoryForm();
        } catch (e) {
            toast(e.message);
        }
    });

    $('btn-toggle-import').addEventListener('click', () => {
        $('import-form').hidden = !$('import-form').hidden;
    });

    $('import-form').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const text = $('import-text').value;
        if (!text.trim()) return;
        // split and encrypt here so the server never receives readable text
        const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
        if (!lines.length) return;
        try {
            const titles = [];
            for (const line of lines) {
                titles.push(await enc(line.slice(0, 255)));
            }
            await api('/api/sessions/' + auth.code + '/stories/import',
                {method: 'POST', body: JSON.stringify({titles: titles})});
            $('import-text').value = '';
            $('import-form').hidden = true;
        } catch (e) {
            toast(e.message);
        }
    });

    // ---------- export ----------
    // The server cannot render these files: story titles and participant names
    // are end-to-end encrypted, so only a client holding the room key can
    // produce readable output. It hands over a bundle and we format it here.
    async function download(format) {
        try {
            const bundle = await api('/api/sessions/' + auth.code + '/export');
            for (const story of bundle.stories || []) {
                await decryptStory(story);
                for (const round of story.rounds || []) {
                    for (const vote of round.votes || []) {
                        vote.displayName = await dec(vote.displayName);
                    }
                }
            }
            const content = format === 'csv' ? toCsv(bundle) : JSON.stringify(bundle, null, 2);
            const type = format === 'csv' ? 'text/csv;charset=utf-8' : 'application/json';
            const blob = new Blob([content], {type: type});
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'session-' + auth.code + '.' + format;
            a.click();
            URL.revokeObjectURL(url);
        } catch (e) {
            toast(e.message);
        }
    }

    function csvCell(value) {
        if (value === null || value === undefined) return '';
        const s = String(value);
        return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
    }

    function toCsv(bundle) {
        const rows = [['story_title', 'story_status', 'final_estimate',
            'revealed_at', 'participant', 'card_value', 'submitted_at']];
        (bundle.stories || []).forEach(story => {
            const revealed = (story.rounds || []).filter(r => (r.votes || []).length);
            if (!revealed.length) {
                rows.push([story.title, story.status, story.finalEstimate, '', '', '', '']);
                return;
            }
            revealed.forEach(round => {
                round.votes.forEach(vote => {
                    rows.push([story.title, story.status, story.finalEstimate,
                        round.revealedAt, vote.displayName, vote.cardValue, vote.submittedAt]);
                });
            });
        });
        return rows.map(r => r.map(csvCell).join(',')).join('\n') + '\n';
    }

    $('btn-export-csv').addEventListener('click', () => download('csv'));
    $('btn-export-json').addEventListener('click', () => download('json'));

    /**
     * Reads the room key from the invite link's fragment, then removes it from
     * the visible URL. The key stays in memory and sessionStorage; dropping it
     * from the address bar keeps it out of screenshots and shared URLs.
     */
    async function adoptKeyFromFragment() {
        const match = /(?:^|[#&])k=([A-Za-z0-9_-]+)/.exec(location.hash || '');
        if (!match) return false;
        try {
            await useRoomKey(match[1]);
            history.replaceState(null, '', location.pathname + location.search);
            return true;
        } catch (e) {
            toast('The invite link carries an invalid room key');
            return false;
        }
    }

    // ---------- boot ----------
    async function boot() {
        loadDecks();
        if (!cryptoAvailable()) {
            toast('Encryption unavailable: open this app over HTTPS (or localhost)', 12000);
        }
        const params = new URLSearchParams(location.search);
        const joinCode = params.get('join');
        const fromLink = cryptoAvailable() && await adoptKeyFromFragment();
        if (joinCode) {
            $('join-code').value = joinCode.toUpperCase();
            $('join-your-name').focus();
        }
        // the link already supplied the key, so hide the manual field
        $('join-room-key-row').hidden = fromLink;

        if (auth && auth.code) {
            // resume an existing session (reconnect within grace window, FR-10)
            try {
                if (!room.key && auth.roomKey) {
                    await useRoomKey(auth.roomKey);
                }
                snapshot = await decryptSnapshot(await api('/api/sessions/' + auth.code));
                if (snapshot.status === 'ACTIVE' && me()) {
                    enterSession();
                    return;
                }
            } catch (e) { /* token expired, session gone, or key unusable */ }
            saveAuth(null);
        }
        showHome();
    }

    boot();
})();
