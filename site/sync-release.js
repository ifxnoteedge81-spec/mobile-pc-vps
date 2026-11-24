// Runs during Netlify Git-connected builds to sync latest GitHub Release APK metadata
const fs = require("fs");
const path = require("path");

async function main() {
  const versionPath = path.join(__dirname, "downloads", "version.json");
  let current = {};
  try {
    current = JSON.parse(fs.readFileSync(versionPath, "utf8"));
  } catch (_) {}

  const { execSync } = require("child_process");
  let repoUrl = process.env.REPOSITORY_URL || "";
  if (!repoUrl) {
    try {
      repoUrl = execSync("git config --get remote.origin.url", { encoding: "utf8" }).trim();
    } catch (_) {}
  }
  const match = repoUrl.match(/github\.com[:/]([^/\s]+\/[^/\s]+?)(?:\.git)?\/?$/i);
  const repoSlug = process.env.GITHUB_REPOSITORY || (match && match[1]) || current.repo || "";

  if (!repoSlug) {
    console.log("No GitHub repository slug detected; keeping existing version.json");
    return;
  }

  current.repo = repoSlug;

  async function fetchLatestRelease() {
    const headers = { "User-Agent": "PersonalAiComputer-NetlifySync" };
    const latestResp = await fetch(`https://api.github.com/repos/${repoSlug}/releases/latest`, { headers });
    if (latestResp.ok) return latestResp.json();
    const listResp = await fetch(`https://api.github.com/repos/${repoSlug}/releases?per_page=1`, { headers });
    if (listResp.ok) {
      const list = await listResp.json();
      if (Array.isArray(list) && list.length > 0) return list[0];
    }
    console.log(`GitHub Releases API returned ${latestResp.status}/${listResp.status}; writing repo slug only.`);
    return null;
  }

  try {
    const release = await fetchLatestRelease();
    if (!release) {
      fs.writeFileSync(versionPath, JSON.stringify(current, null, 2));
      return;
    }
    const apkAsset = (release.assets || []).find((a) => a.name && a.name.endsWith(".apk"));
    const shaAsset = (release.assets || []).find((a) => a.name && a.name.endsWith(".sha256"));

    if (apkAsset) {
      let sha256 = current.sha256 || "See GitHub Release .sha256 asset";
      if (shaAsset && shaAsset.browser_download_url) {
        try {
          const shaResp = await fetch(shaAsset.browser_download_url);
          if (shaResp.ok) {
            const txt = await shaResp.text();
            const parsed = txt.trim().split(/\s+/)[0];
            if (parsed && parsed.length === 64) sha256 = parsed;
          }
        } catch (_) {}
      }

      const updated = {
        ...current,
        appName: "Personal AI Computer",
        version: current.version && current.version !== "pending-ci-build" ? current.version : (release.tag_name || "latest"),
        buildType: current.buildType && current.buildType !== "unbuilt-template" ? current.buildType : "github-release",
        releaseDate: apkAsset.updated_at || release.published_at || current.releaseDate,
        apkFile: current.apkFile || "downloads/personal-ai-computer.apk",
        releaseDownloadUrl: apkAsset.browser_download_url,
        repo: repoSlug,
        sizeBytes: apkAsset.size || current.sizeBytes || 0,
        sha256
      };
      fs.writeFileSync(versionPath, JSON.stringify(updated, null, 2));
      console.log(`Synced APK release ${updated.version} (${updated.sizeBytes} bytes) from ${repoSlug}`);
      return;
    }
  } catch (err) {
    console.log("GitHub sync skipped:", err.message);
  }

  fs.writeFileSync(versionPath, JSON.stringify(current, null, 2));
}

main().catch((err) => {
  console.log("sync-release non-fatal:", err.message);
});
