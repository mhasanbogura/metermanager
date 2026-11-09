const fs = require('fs');
const filePath = 'js/app.js';
let content = fs.readFileSync(filePath, 'utf8');

const oldCode = `            <button class="mc-action" title="\${esc(t('btn.refresh'))}" onclick="event.stopPropagation();window.ppRefresh('\${m.id}')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
            </button>`;

const newCode = `        <div class="mc-top">
          <span class="mc-provider">\${m.provider === 'nesco' ? 'NESCO' : 'DESCO' + (m.sys === 'unified' ? ' · Unified' : '')}</span>
          <div class="mc-actions">
            <button class="mc-action" title="\${esc(t('btn.refresh'))}" onclick="event.stopPropagation();window.ppRefresh('\${m.id}')">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
            </button>
          </div>
        </div>`;

// Using replace with a literal string, but the template string in the file has variables.
// Since the `oldCode` is hard to match exactly, I'll use a regex that matches the button block.
const regex = /            <button class="mc-action" title="\$\{esc\(t\('btn\.refresh'\)\)\}" onclick="event\.stopPropagation\(\);window\.ppRefresh\('\$\{m\.id\}'\)">[\s\S]*?<\/button>/;

content = content.replace(regex, newCode);
fs.writeFileSync(filePath, content);
