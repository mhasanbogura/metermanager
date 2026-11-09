// Robust replacement that finds the mc-action refresh button inside meter-card
// and wraps it in a proper mc-top + mc-actions structure next to mc-provider.
const fs = require('fs');
const filePath = 'js/app.js';
let content = fs.readFileSync(filePath, 'utf8');

const bIdx = content.indexOf('mc-action" title="${esc(t(\'btn.refresh\')")');
if (bIdx === -1) { console.log('Not found'); process.exit(1); }

const cardStart = content.lastIndexOf('<div class="meter-card"', bIdx);
const numberStart = content.indexOf('<div class="mc-number">', bIdx);

if (cardStart === -1 || numberStart === -1) { console.log('Card boundary not found'); process.exit(1); }

const oldBlock = content.slice(cardStart, numberStart);
const newBlock = `        <div class="mc-top">
          <span class="mc-provider">${m.provider === 'nesco' ? 'NESCO' : 'DESCO' + (m.sys === 'unified' ? ' · Unified' : '')}</span>
          <div class="mc-actions">
            <button class="mc-action" title="${esc(t('btn.refresh'))}" onclick="event.stopPropagation();window.ppRefresh('${m.id}')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
            </button>
          </div>
        </div>`;

content = content.slice(0, cardStart) + newBlock + content.slice(numberStart);
fs.writeFileSync(filePath, content);
console.log('Done. Total length:', content.length);
