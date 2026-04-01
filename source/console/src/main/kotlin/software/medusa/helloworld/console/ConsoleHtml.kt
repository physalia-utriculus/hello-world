package software.medusa.helloworld.console

fun renderConsolePage(): String {
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Session Console</title>
            <style>
                :root {
                    color-scheme: light;
                    font-family: Inter, system-ui, sans-serif;
                    background: #f5f7fb;
                    color: #172033;
                }
                body {
                    margin: 0;
                    min-height: 100vh;
                    background: linear-gradient(180deg, #eef4ff 0%, #f8fbff 100%);
                }
                .shell {
                    max-width: 1200px;
                    margin: 0 auto;
                    padding: 24px;
                }
                .hero {
                    display: grid;
                    gap: 16px;
                    grid-template-columns: 1.5fr 1fr;
                    margin-bottom: 24px;
                }
                .panel {
                    background: rgba(255, 255, 255, 0.9);
                    border: 1px solid #d8e0f0;
                    border-radius: 18px;
                    box-shadow: 0 20px 60px rgba(70, 90, 130, 0.08);
                    padding: 20px;
                }
                h1, h2, h3, p {
                    margin-top: 0;
                }
                button {
                    border: 0;
                    border-radius: 999px;
                    background: #2f6df6;
                    color: white;
                    padding: 12px 18px;
                    font-weight: 700;
                    cursor: pointer;
                }
                button:disabled {
                    opacity: 0.6;
                    cursor: wait;
                }
                .layout {
                    display: grid;
                    grid-template-columns: 1.1fr 1fr;
                    gap: 24px;
                }
                .session-list {
                    display: grid;
                    gap: 12px;
                }
                .session-card {
                    border: 1px solid #d8e0f0;
                    border-radius: 16px;
                    padding: 16px;
                    cursor: pointer;
                    background: #fff;
                }
                .session-card.selected {
                    border-color: #2f6df6;
                    box-shadow: 0 0 0 3px rgba(47, 109, 246, 0.15);
                }
                .session-meta {
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    font-size: 14px;
                }
                .badge {
                    display: inline-flex;
                    align-items: center;
                    border-radius: 999px;
                    padding: 4px 10px;
                    font-size: 12px;
                    font-weight: 700;
                    background: #e6ecfb;
                    color: #244a9f;
                }
                .badge.RUNNING { background: #e7f7ef; color: #1a7f45; }
                .badge.SUCCEEDED { background: #e8f7f3; color: #0d7a63; }
                .badge.FAILED { background: #fdebec; color: #b42318; }
                .progress {
                    height: 10px;
                    background: #e8edf7;
                    border-radius: 999px;
                    overflow: hidden;
                    margin-top: 12px;
                }
                .progress > div {
                    height: 100%;
                    background: linear-gradient(90deg, #2f6df6 0%, #57b8ff 100%);
                }
                .events {
                    display: grid;
                    gap: 12px;
                    max-height: 720px;
                    overflow: auto;
                }
                .event {
                    border-left: 3px solid #2f6df6;
                    padding-left: 12px;
                }
                .muted {
                    color: #56627a;
                }
                .error {
                    color: #b42318;
                    min-height: 20px;
                }
                @media (max-width: 960px) {
                    .hero, .layout {
                        grid-template-columns: 1fr;
                    }
                }
            </style>
        </head>
        <body>
            <main class="shell">
                <section class="hero">
                    <div class="panel">
                        <h1>Session Console</h1>
                        <p class="muted">Start a long-running Cloud Run Job session and watch its Firestore-backed progress refresh live.</p>
                        <button id="start-session">Start session</button>
                        <p id="action-error" class="error"></p>
                    </div>
                    <div class="panel">
                        <h2>How it works</h2>
                        <p class="muted">The console creates a session, triggers a Cloud Run Job execution, and refreshes session state every few seconds.</p>
                    </div>
                </section>

                <section class="layout">
                    <div class="panel">
                        <h2>Sessions</h2>
                        <div id="session-list" class="session-list"></div>
                    </div>
                    <div class="panel">
                        <h2>Selected session</h2>
                        <div id="session-detail" class="muted">Start or select a session to observe it.</div>
                    </div>
                </section>
            </main>

            <script>
                const state = {
                    selectedSessionId: null,
                    sessions: []
                };

                const listEl = document.getElementById('session-list');
                const detailEl = document.getElementById('session-detail');
                const startButton = document.getElementById('start-session');
                const actionError = document.getElementById('action-error');

                startButton.addEventListener('click', async () => {
                    actionError.textContent = '';
                    startButton.disabled = true;
                    try {
                        const response = await fetch('/sessions', { method: 'POST' });
                        if (!response.ok) {
                            throw new Error(await response.text());
                        }
                        const data = await response.json();
                        state.selectedSessionId = data.sessionId;
                        await refreshSessions();
                        await refreshDetail();
                    } catch (error) {
                        actionError.textContent = error.message || 'Failed to start session.';
                    } finally {
                        startButton.disabled = false;
                    }
                });

                function sessionCard(session) {
                    const selectedClass = session.id === state.selectedSessionId ? 'selected' : '';
                    const waitingText = 'Waiting for updates';
                    const progress = session.progressPercent || 0;
                    return '' +
                        '<article class="session-card ' + selectedClass + '" data-session-id="' + session.id + '">' +
                            '<div class="session-meta">' +
                                '<strong>' + session.id + '</strong>' +
                                '<span class="badge ' + session.status + '">' + session.status + '</span>' +
                            '</div>' +
                            '<p>' + (session.currentStep || waitingText) + '</p>' +
                            '<div class="progress"><div style="width:' + progress + '%"></div></div>' +
                            '<div class="session-meta muted">' +
                                '<span>' + progress + '%</span>' +
                                '<span>' + new Date(session.updatedAt).toLocaleString() + '</span>' +
                            '</div>' +
                        '</article>';
                }

                function renderSessions() {
                    if (state.sessions.length === 0) {
                        listEl.innerHTML = '<p class="muted">No sessions yet.</p>';
                        return;
                    }
                    listEl.innerHTML = state.sessions.map(sessionCard).join('');
                    listEl.querySelectorAll('[data-session-id]').forEach(element => {
                        element.addEventListener('click', () => {
                            state.selectedSessionId = element.getAttribute('data-session-id');
                            renderSessions();
                            refreshDetail();
                        });
                    });
                }

                function renderEvents(events) {
                    if (events.length === 0) {
                        return '<p class="muted">No events yet.</p>';
                    }

                    return events.map(event => {
                        const step = event.step ? ' - ' + event.step : '';
                        const progress = event.progressPercent !== null && event.progressPercent !== undefined ? ' - ' + event.progressPercent + '%' : '';
                        const details = event.details ? '<div class="muted">' + event.details + '</div>' : '';
                        return '' +
                            '<div class="event">' +
                                '<strong>' + event.message + '</strong>' +
                                '<div class="muted">' + new Date(event.timestamp).toLocaleString() + step + progress + '</div>' +
                                details +
                            '</div>';
                    }).join('');
                }

                function renderDetail(data) {
                    if (!data) {
                        detailEl.innerHTML = '<p class="muted">Start or select a session to observe it.</p>';
                        return;
                    }

                    const session = data.session;
                    const progress = session.progressPercent || 0;
                    const execution = session.executionName ? '<p class="muted">Execution: ' + session.executionName + '</p>' : '';
                    const result = session.resultSummary ? '<p>' + session.resultSummary + '</p>' : '';
                    const error = session.errorMessage ? '<p class="error">' + session.errorMessage + '</p>' : '';

                    detailEl.innerHTML = '' +
                        '<div class="session-meta">' +
                            '<strong>' + session.id + '</strong>' +
                            '<span class="badge ' + session.status + '">' + session.status + '</span>' +
                        '</div>' +
                        '<p>' + (session.currentStep || 'Waiting for updates') + '</p>' +
                        '<div class="progress"><div style="width:' + progress + '%"></div></div>' +
                        '<p class="muted">Created ' + new Date(session.createdAt).toLocaleString() + '</p>' +
                        execution +
                        result +
                        error +
                        '<h3>Events</h3>' +
                        '<div class="events">' + renderEvents(data.events) + '</div>';
                }

                async function refreshSessions() {
                    const response = await fetch('/api/sessions');
                    if (!response.ok) {
                        return;
                    }
                    const data = await response.json();
                    state.sessions = data.sessions;
                    if (!state.selectedSessionId && state.sessions.length > 0) {
                        state.selectedSessionId = state.sessions[0].id;
                    }
                    renderSessions();
                }

                async function refreshDetail() {
                    if (!state.selectedSessionId) {
                        renderDetail(null);
                        return;
                    }
                    const response = await fetch('/api/sessions/' + state.selectedSessionId);
                    if (response.status === 404) {
                        renderDetail(null);
                        return;
                    }
                    if (!response.ok) {
                        return;
                    }
                    renderDetail(await response.json());
                }

                async function tick() {
                    await refreshSessions();
                    await refreshDetail();
                }

                tick();
                setInterval(tick, 3000);
            </script>
        </body>
        </html>
    """.trimIndent()
}
