const fs = require('fs');
const filePath = 'js/app.js';
let content = fs.readFileSync(filePath, 'utf8');

// The regex needs to match the structure precisely. 
// Based on the read output, lines 1907-1909 are:
//            <button class="mc-action" title="${esc(t('btn.refresh'))}" onclick="event.stopPropagation();window.ppRefresh('${m.id}')">
//              <svg ...></svg>
//            </button>

// Let's use a regex that is less sensitive to exact whitespace for the inner part, 
// but captures the indentation of the button.
const regex = /([ ]+)<button class="mc-action" title="\$\{esc\(t\('btn\.refresh'\)\)\}" onclick="event\.stopPropagation\(\);window\.ppRefresh\('\$\{m\.id\}'\)">[\s\S]*?<\/button>/;

const newCode = (indent) => `${indent}<div class="mc-top">
${indent}  <span class="mc-provider">\${m.provider === 'nesco' ? 'NESCO' : 'DESCO' + (m.sys === 'unified' ? ' · Unified' : '')}</span>
${indent}  <div class="mc-actions">
${indent}    <button class="mc-action" title="\${esc(t('btn.refresh'))}" onclick="event.stopPropagation();window.ppRefresh('\${m.id}')">
${indent}      <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
${indent}    </button>
${indent}  </div>
${indent}</div>`;

content = content.replace(regex, (match, indent) => newCode(indent));
fs.writeFileSync(filePath, content);
