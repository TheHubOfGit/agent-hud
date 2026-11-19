#!/usr/bin/env node
/**
 * Optional native Claude Code hook script.
 * Add to ~/.claude/settings.json under hooks.PreToolUse / Notification / Stop
 * to get ~5ms instant watch alerts and Watch Allow/Deny decisions!
 */
const BRIDGE_URL = process.env.AGENT_HUD_URL || 'http://127.0.0.1:8765';
const SYNC_MODE = process.argv.includes('--sync');

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => { raw += chunk; });
process.stdin.on('end', async () => {
  try {
    const payload = raw ? JSON.parse(raw) : {};
    const url = `${BRIDGE_URL}/hook/claude${SYNC_MODE ? '?sync=1' : ''}`;
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (SYNC_MODE && resp.ok) {
      const data = await resp.json();
      if (data.decision === 'allow' || data.decision === 'deny') {
        process.stdout.write(JSON.stringify({
          hookSpecificOutput: {
            hookEventName: 'PreToolUse',
            permissionDecision: data.decision,
            permissionDecisionReason: `Decided from Pixel Watch 3 (${data.decision})`,
          },
        }));
      }
    }
  } catch {
    // Never block Claude if the watch bridge isn't running
  }
  process.exit(0);
});
