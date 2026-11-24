# Netlify Static APK Deployment Setup

Netlify is used strictly as a static APK download host (`site/`).

## One-Time Setup
1. Create a Netlify site (drag-and-drop `site/` once or create via Netlify CLI) and copy its **Site ID**.
2. Create a Personal Access Token in Netlify User Settings -> Applications.
3. Add two GitHub Repository Secrets:
   - `NETLIFY_AUTH_TOKEN`
   - `NETLIFY_SITE_ID`
4. Push to `main` or push a tag `v1.0.0` to trigger `.github/workflows/android-build.yml`.
