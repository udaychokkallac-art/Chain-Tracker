        let previousHeight = 0;
        let isFirstLoad = true;

        // Clock display
        function updateClock() {
            const now = new Date();
            const h = String(now.getUTCHours()).padStart(2, '0');
            const m = String(now.getUTCMinutes()).padStart(2, '0');
            const s = String(now.getUTCSeconds()).padStart(2, '0');
            const clockEl = document.getElementById('utcClock');
            if (clockEl) clockEl.textContent = `${h}:${m}:${s} UTC`;
        }
        setInterval(updateClock, 1000);
        updateClock();

        // Copy Hash
        function copyHash() {
            const hash = document.getElementById('valHash').textContent.trim();
            if (!hash || hash.includes('00000000000000000000000000000000') || hash.includes('skeleton')) return;
            navigator.clipboard.writeText(hash).then(() => {
                const btnText = document.getElementById('copyText');
                btnText.textContent = 'COPIED';
                setTimeout(() => { btnText.textContent = 'COPY HASH'; }, 1800);
            }).catch(() => {});
        }

        function truncateHash(hash) {
            if (!hash || hash.length < 24) return hash;
            return hash.substring(0, 14) + '...' + hash.substring(hash.length - 10);
        }

        function updateStatusBadge(status) {
            const badge = document.getElementById('statusBadge');
            const dot = document.getElementById('statusDot');
            const text = document.getElementById('statusText');
            if (!badge || !dot || !text) return;

            text.textContent = status;
            if (status === 'HEALTHY') {
                badge.style.background = 'rgba(34, 197, 94, 0.08)';
                badge.style.color = 'var(--accent-green)';
                badge.style.borderColor = 'rgba(34, 197, 94, 0.25)';
                dot.style.backgroundColor = 'var(--accent-green)';
            } else if (status === 'WARNING') {
                badge.style.background = 'rgba(245, 158, 11, 0.08)';
                badge.style.color = 'var(--accent-amber)';
                badge.style.borderColor = 'rgba(245, 158, 11, 0.25)';
                dot.style.backgroundColor = 'var(--accent-amber)';
            } else {
                badge.style.background = 'rgba(239, 68, 68, 0.08)';
                badge.style.color = 'var(--accent-red)';
                badge.style.borderColor = 'rgba(239, 68, 68, 0.25)';
                dot.style.backgroundColor = 'var(--accent-red)';
            }
        }

        // Opal-Style Canvas Waveform
        const canvas = document.getElementById('telemetryCanvas');
        const ctx = canvas.getContext('2d');
        let mouseX = 0.5, mouseY = 0.5, isHovering = false;

        function resizeCanvas() {
            canvas.width = canvas.offsetWidth * window.devicePixelRatio;
            canvas.height = canvas.offsetHeight * window.devicePixelRatio;
            ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
        }
        window.addEventListener('resize', resizeCanvas);
        resizeCanvas();

        canvas.addEventListener('mousemove', (e) => {
            const rect = canvas.getBoundingClientRect();
            mouseX = (e.clientX - rect.left) / rect.width;
            mouseY = (e.clientY - rect.top) / rect.height;
            isHovering = true;
        });
        canvas.addEventListener('mouseleave', () => { isHovering = false; });

        let frame = 0;
        function renderWave() {
            const w = canvas.offsetWidth;
            const h = canvas.offsetHeight;
            ctx.clearRect(0, 0, w, h);

            // Grid lines
            ctx.strokeStyle = 'rgba(255, 255, 255, 0.03)';
            ctx.lineWidth = 1;
            for (let x = 0; x < w; x += 40) {
                ctx.beginPath();
                ctx.moveTo(x, 0);
                ctx.lineTo(x, h);
                ctx.stroke();
            }

            // Draw technical waveform
            ctx.beginPath();
            ctx.strokeStyle = 'rgba(255, 255, 255, 0.7)';
            ctx.lineWidth = 1.5;

            const baseFreq = 0.015 + (isHovering ? (mouseX - 0.5) * 0.01 : 0);
            const amplitude = (h * 0.25) * (isHovering ? 0.7 + (1 - mouseY) * 0.6 : 0.8);
            const centerY = h / 2;

            for (let x = 0; x < w; x++) {
                const y = centerY + Math.sin(x * baseFreq + frame * 0.03) * amplitude * Math.cos(x * 0.003);
                if (x === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.stroke();

            // Glow line underneath
            ctx.beginPath();
            ctx.strokeStyle = 'rgba(245, 158, 11, 0.25)';
            ctx.lineWidth = 3;
            for (let x = 0; x < w; x++) {
                const y = centerY + Math.sin(x * (baseFreq * 0.9) + frame * 0.02) * (amplitude * 0.6);
                if (x === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.stroke();

            frame++;
            requestAnimationFrame(renderWave);
        }
        renderWave();

        // Main telemetry updater
        async function updateDashboard() {
            try {
                const res = await fetch('/api/telemetry');
                if (!res.ok) throw new Error('Network error');
                const data = await res.json();

                if (isFirstLoad) {
                    document.querySelectorAll('.skeleton').forEach(el => el.classList.remove('skeleton'));
                    isFirstLoad = false;
                }

                // Update Height
                const heightEl = document.getElementById('valHeight');
                if (data.height > previousHeight && previousHeight > 0) {
                    heightEl.classList.add('flash');
                    setTimeout(() => heightEl.classList.remove('flash'), 1200);
                    const diff = data.height - previousHeight;
                    document.getElementById('subHeight').textContent = `SYNCHRONIZED // +${diff} NEW BLOCK CONFIRMED`;
                }
                if (data.height > 0) {
                    heightEl.textContent = '#' + data.height.toLocaleString();
                    previousHeight = data.height;
                }

                // Update Price
                if (data.price > 0) {
                    document.getElementById('valPrice').textContent = '$' + data.price.toLocaleString(undefined, {minimumFractionDigits: 2, maximumFractionDigits: 2});
                }

                // Update Latency
                const latEl = document.getElementById('valLatency');
                latEl.textContent = data.latency + 'ms';
                const latMeta = document.getElementById('latencyMeta');
                if (data.latency < 1000) {
                    latEl.style.color = 'var(--text-primary)';
                    latMeta.textContent = 'OPTIMAL PEER SPEED';
                } else if (data.latency < 2000) {
                    latEl.style.color = 'var(--accent-amber)';
                    latMeta.textContent = 'ELEVATED ROUNDTRIP';
                } else {
                    latEl.style.color = 'var(--accent-red)';
                    latMeta.textContent = 'DEGRADED LATENCY';
                }

                // Update Hash
                document.getElementById('valHash').textContent = data.hash || 'WAITING FOR DATA...';
                if (document.getElementById('valPrevHash')) {
                    document.getElementById('valPrevHash').textContent = data.previousHash || 'SYNCHRONIZING PARENT TIP...';
                }

                // Update Stats
                document.getElementById('valUptime').textContent = data.uptime;
                document.getElementById('valBlocks').textContent = data.totalBlocksSeen;
                document.getElementById('valAlerts').textContent = data.totalAlertsSent;
                if (document.getElementById('valReorgs')) {
                    document.getElementById('valReorgs').textContent = data.reorgCount || 0;
                }
                if (document.getElementById('valWsMode')) {
                    document.getElementById('valWsMode').textContent = data.wsConnected ? '⚡ WEBSOCKET (LIVE)' : 'REST HEARTBEAT';
                    document.getElementById('valWsMode').style.color = data.wsConnected ? 'var(--accent-green)' : 'var(--accent-amber)';
                }

                updateStatusBadge(data.status);

                // Update History Table
                const tbody = document.getElementById('blocksTableBody');
                if (data.history && data.history.length > 0) {
                    tbody.innerHTML = data.history.map(b => `
                        <tr>
                            <td class="highlight">#${b.height.toLocaleString()}</td>
                            <td class="mono">${truncateHash(b.hash)}</td>
                            <td>${b.txCount.toLocaleString()} txs</td>
                            <td class="highlight">$${b.price.toLocaleString(undefined, {minimumFractionDigits: 2})}</td>
                            <td class="mono">${b.time}</td>
                        </tr>
                    `).join('');
                }

                // Update Event Log
                const eventList = document.getElementById('eventList');
                if (data.events && data.events.length > 0) {
                    eventList.innerHTML = data.events.map((e, idx) => {
                        const num = String(idx + 1).padStart(2, '0');
                        let badgeClass = 'event-badge';
                        if (e.type === 'NEW_BLOCK') badgeClass += ' new_block';
                        else if (e.type === 'REORG') badgeClass += ' reorg';
                        else if (e.type === 'PRICE_ALERT') badgeClass += ' price_alert';
                        return `
                            <div class="event-row">
                                <span class="event-index">${num}</span>
                                <span class="event-time-tag">${e.time}</span>
                                <span class="${badgeClass}">${e.type}</span>
                                <span class="event-desc">${e.message}</span>
                            </div>
                        `;
                    }).join('');
                }

            } catch (err) {
                console.error(err);
                if (!isFirstLoad) {
                    updateStatusBadge('DISCONNECTED');
                }
            }
        }

        setInterval(updateDashboard, 3000);
        updateDashboard();