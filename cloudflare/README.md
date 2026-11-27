# SentenceLab Pages proxy

This independent Cloudflare Pages project forwards only the explicitly listed
SentenceLab API routes to the existing HTTPS origin. It does not expose Chess Mine,
Blender MCP or port 8765, and it does not alter Oracle firewall settings.
Authorization and Content-Type are forwarded; redirects, cookies and caching
are disabled. The origin still verifies Google ID tokens and session credentials.

## Deploy

In Cloudflare Dashboard > Workers & Pages > Create application > Pages > Connect to Git,
select sihoo7005/SentenceLab. Configure:

- Project name: sentencelab (if available)
- Production branch: main
- Framework preset: None
- Root directory: cloudflare
- Build command: leave blank
- Build output directory: public

Pages will discover the functions directory alongside public.
Alternatively from this directory, with Wrangler already authenticated:
`npx wrangler pages deploy public --project-name sentencelab`.

Run the mock regression test: `node test-proxy.mjs`.
After deployment, verify `https://sentencelab.pages.dev/sentencelab/health`
from a connection without VPN and check an authentication challenge.
If the assigned hostname differs, use that hostname instead.

Only after successful verification, change the GitHub Actions repository
variable API_BASE_URL to https://sentencelab.pages.dev/sentencelab and build
the APK with the existing stable signing key. The old URL remains the current
default until the new deployment is verified; no existing APK is silently changed.

The v0.10.0 backend schema upgrade is a separate deployment prerequisite for
synchronizing mode-specific completion records.
