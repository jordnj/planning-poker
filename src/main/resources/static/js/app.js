/* Planning Poker SPA client: REST + STOMP over SockJS. */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);

    // ---------- client state ----------
    let auth = loadAuth();            // { token, code, participantId }
    let snapshot = null;              // latest SessionSnapshot
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
        const deckValue = $('create-deck').value;
        const body = {
            name: $('create-session-name').value.trim(),
            facilitatorName: $('create-your-name').value.trim(),
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
        try {
            const res = await api('/api/sessions', {method: 'POST', body: JSON.stringify(body)});
            saveAuth({token: res.token, code: res.session.code, participantId: res.participant.id});
            snapshot = res.session;
            enterSession();
            toast('Session created — share code ' + res.session.code);
        } catch (e) {
            toast(e.message);
        }
    });

    // ---------- home: join ----------
    $('join-form').addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const code = $('join-code').value.trim().toUpperCase();
        const body = {
            displayName: $('join-your-name').value.trim(),
            role: $('join-as-observer').checked ? 'OBSERVER' : 'VOTER'
        };
        try {
            const res = await api('/api/sessions/' + encodeURIComponent(code) + '/participants',
                {method: 'POST', body: JSON.stringify(body)});
            saveAuth({token: res.token, code: res.session.code, participantId: res.participant.id});
            snapshot = res.session;
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
        const url = location.origin + '/?join=' + auth.code;
        navigator.clipboard.writeText(url).then(
            () => toast('Invite link copied'),
            () => toast(url, 8000));
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
                    snapshot = JSON.parse(frame.body);
                    render();
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
            snapshot = await api('/api/sessions/' + auth.code);
            render();
        } catch (e) {
            toast(e.message);
        }
    }

    // ---------- server events ----------
    function handleEvent(event) {
        if (!snapshot) { refetchSnapshot(); return; }
        const p = event.payload;
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
                refetchSnapshot();
                break;
            case 'SESSION_CLOSED':
                toast('The session was closed');
                leaveSession(true);
                break;
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
        const body = {
            title: $('story-title').value.trim(),
            description: $('story-description').value.trim(),
            referenceUrl: $('story-url').value.trim()
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
        try {
            await api('/api/sessions/' + auth.code + '/stories/import',
                {method: 'POST', body: JSON.stringify({text: text})});
            $('import-text').value = '';
            $('import-form').hidden = true;
        } catch (e) {
            toast(e.message);
        }
    });

    // ---------- export ----------
    async function download(format) {
        try {
            const res = await fetch('/api/sessions/' + auth.code + '/export?format=' + format, {
                headers: {'Authorization': 'Bearer ' + auth.token}
            });
            if (!res.ok) throw new Error('Export failed (' + res.status + ')');
            const blob = await res.blob();
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

    $('btn-export-csv').addEventListener('click', () => download('csv'));
    $('btn-export-json').addEventListener('click', () => download('json'));

    // ---------- boot ----------
    async function boot() {
        loadDecks();
        const params = new URLSearchParams(location.search);
        const joinCode = params.get('join');
        if (joinCode) {
            $('join-code').value = joinCode.toUpperCase();
            $('join-your-name').focus();
        }
        if (auth && auth.code) {
            // resume an existing session (reconnect within grace window, FR-10)
            try {
                snapshot = await api('/api/sessions/' + auth.code);
                if (snapshot.status === 'ACTIVE' && me()) {
                    enterSession();
                    return;
                }
            } catch (e) { /* token expired or session gone */ }
            saveAuth(null);
        }
        showHome();
    }

    boot();
})();
