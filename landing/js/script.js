/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    initPipelineSimulator();
});

/* ==========================================================================
   Tab Navigation Functionality
   ========================================================================== */
function initTabs() {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabPanes = document.querySelectorAll('.tab-pane');

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const targetTab = btn.getAttribute('data-tab');

            tabBtns.forEach(b => b.classList.remove('active'));
            tabPanes.forEach(p => p.classList.remove('active'));

            btn.classList.add('active');
            const activePane = document.getElementById(targetTab);
            if (activePane) {
                activePane.classList.add('active');
            }
        });
    });
}

/* ==========================================================================
   Pipeline Simulator Functionality
   ========================================================================== */
function initPipelineSimulator() {
    const logsContainer = document.getElementById('console-logs');
    if (!logsContainer) return;

    // Pulse indicators
    const pulses = {
        1: document.getElementById('pulse-1'),
        2: document.getElementById('pulse-2'),
        3: document.getElementById('pulse-3'),
        4: document.getElementById('pulse-4')
    };

    // Flow stages
    const stages = {
        exporter: document.getElementById('stage-exporter'),
        ingest: document.getElementById('stage-ingest'),
        enrich: document.getElementById('stage-enrich'),
        storage: document.getElementById('stage-storage'),
        visual: document.getElementById('stage-visual')
    };

    // Helper to clear active state on all stages
    function clearActiveStages() {
        Object.values(stages).forEach(stage => {
            if (stage) stage.classList.remove('active');
        });
    }

    // Helper to highlight a stage
    function highlightStage(stageKey) {
        clearActiveStages();
        if (stages[stageKey]) {
            stages[stageKey].classList.add('active');
        }
    }

    // Helper to log a console line
    function logMessage(text, type = '') {
        const line = document.createElement('div');
        line.className = 'console-line';
        if (type) {
            line.classList.add(type);
        }
        
        const timestamp = new Date().toLocaleTimeString();
        line.innerHTML = `<span class="text-dim">[${timestamp}]</span> ${text}`;
        
        logsContainer.appendChild(line);
        logsContainer.scrollTop = logsContainer.scrollHeight;

        // Keep buffer size limited to 20 lines
        while (logsContainer.children.length > 20) {
            logsContainer.removeChild(logsContainer.firstChild);
        }
    }

    // Simulated network scenario logs
    const scenarios = [
        {
            name: 'Cisco NetFlow v9 ingestion',
            run: async () => {
                highlightStage('exporter');
                logMessage('Ingress: Exporter <span class="text-blue">10.30.12.1</span> (core-router-lax) sending NetFlow v9 template packet', 'text-dim');
                pulses[1].classList.add('animating');
                
                await delay(1200);
                pulses[1].classList.remove('animating');
                highlightStage('ingest');
                logMessage('Parser: Decoded NetFlow v9 packet from 10.30.12.1 containing 8 flow records');
                pulses[2].classList.add('animating');
                
                await delay(1200);
                pulses[2].classList.remove('animating');
                highlightStage('enrich');
                logMessage('Enricher: Fetching SNMP mappings for interfaces (ifIndex: 4, 12)...');
                await delay(500);
                logMessage('Enricher: Resolved ifIndex 4 -> <span class="text-yellow">TenGigabitEthernet1/1</span> (Transit uplink) speed=10000M', 'text-yellow');
                logMessage('Enricher: Resolved GeoIP for Destination 185.190.140.2 -> Country: Netherlands, Org: AS20001');
                logMessage('Enricher: Clock skew repaired: Exporter offset -12200ms adjusted.', 'text-cyan');
                pulses[3].classList.add('animating');
                
                await delay(1200);
                pulses[3].classList.remove('animating');
                highlightStage('storage');
                logMessage('Persist: Inserting records into ClickHouse table <span class="text-cyan">flows_lax_core</span>', 'text-green');
                pulses[4].classList.add('animating');
                
                await delay(1200);
                pulses[4].classList.remove('animating');
                highlightStage('visual');
                logMessage('NOC: Dashboards updated. Ingest rate: 1,482 flows/sec.', 'text-green');
                
                await delay(1500);
                clearActiveStages();
            }
        },
        {
            name: 'sFlow sample ingestion',
            run: async () => {
                highlightStage('exporter');
                logMessage('Ingress: sFlow sample packet (512 bytes) received from <span class="text-blue">172.16.42.254</span> (dist-switch-sfo)', 'text-dim');
                pulses[1].classList.add('animating');
                
                await delay(1200);
                pulses[1].classList.remove('animating');
                highlightStage('ingest');
                logMessage('Parser: Parsed sFlow v5 sample: decoded raw Ethernet/IP header info');
                pulses[2].classList.add('animating');
                
                await delay(1200);
                pulses[2].classList.remove('animating');
                highlightStage('enrich');
                logMessage('Enricher: Locality classification matched: <span class="text-cyan">INTERNAL</span> (172.16.42.12 -> 172.16.42.50)');
                logMessage('Enricher: Hostname reverse DNS resolved: 172.16.42.12 -> app-server-01.sfo.internal');
                pulses[3].classList.add('animating');
                
                await delay(1200);
                pulses[3].classList.remove('animating');
                highlightStage('storage');
                logMessage('Persist: Persisted sFlow record with bytes=1518, packets=1 to ClickHouse', 'text-green');
                pulses[4].classList.add('animating');
                
                await delay(1200);
                pulses[4].classList.remove('animating');
                highlightStage('visual');
                logMessage('NOC: Grafana graph "Internal App Traffic" refreshed.', 'text-green');
                
                await delay(1500);
                clearActiveStages();
            }
        },
        {
            name: 'IPFIX multitenant flow ingestion',
            run: async () => {
                highlightStage('exporter');
                logMessage('Ingress: IPFIX streams received from <span class="text-blue">10.200.5.10</span> (peering-router-edge)', 'text-dim');
                pulses[1].classList.add('animating');
                
                await delay(1200);
                pulses[1].classList.remove('animating');
                highlightStage('ingest');
                logMessage('Parser: Decoded IPFIX flow records for observation domain 1');
                pulses[2].classList.add('animating');
                
                await delay(1200);
                pulses[2].classList.remove('animating');
                highlightStage('enrich');
                logMessage('Enricher: Node routing matched: mapped exporter to Organisation ID: <span class="text-cyan">tenant-transit-nordic</span>');
                logMessage('Enricher: SNMP Interface name resolved: ifIndex 102 -> <span class="text-yellow">xe-0/0/2:0</span> (Peer: AS2914)', 'text-yellow');
                pulses[3].classList.add('animating');
                
                await delay(1200);
                pulses[3].classList.remove('animating');
                highlightStage('storage');
                logMessage('Persist: ClickHouse multi-tenancy row policy enforced. Row written to partition.', 'text-green');
                pulses[4].classList.add('animating');
                
                await delay(1200);
                pulses[4].classList.remove('animating');
                highlightStage('visual');
                logMessage('NOC: Real-time billing metrics updated.', 'text-green');
                
                await delay(1500);
                clearActiveStages();
            }
        }
    ];

    // Helper promise delay
    function delay(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    // Sequence loop manager
    let currentScenario = 0;
    
    async function loop() {
        // Skip work while the tab is hidden; browsers throttle the timer, and
        // there is no point animating stages or appending log lines unseen.
        if (document.hidden) {
            setTimeout(loop, 2000);
            return;
        }
        try {
            await scenarios[currentScenario].run();
        } catch (e) {
            console.error('Simulator error: ', e);
        }

        currentScenario = (currentScenario + 1) % scenarios.length;
        // Delay between scenarios
        setTimeout(loop, 2000);
    }

    // Start loop
    setTimeout(loop, 1000);
}
