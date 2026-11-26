async function loadReleaseMetadata() {
  const btn = document.getElementById("download-btn");
  const statusEl = document.getElementById("build-status");
  try {
    const resp = await fetch("downloads/version.json", { cache: "no-store" });
    if (!resp.ok) throw new Error("Missing version.json");
    let data = await resp.json();

    // Query GitHub Releases live if size/URL missing or to refresh release link
    if ((!data.sizeBytes || data.sizeBytes === 0 || !data.releaseDownloadUrl) && data.repo) {
      try {
        let release = null;
        const ghResp = await fetch(`https://api.github.com/repos/${data.repo}/releases/latest`);
        if (ghResp.ok) {
          release = await ghResp.json();
        } else {
          const listResp = await fetch(`https://api.github.com/repos/${data.repo}/releases?per_page=1`);
          if (listResp.ok) {
            const list = await listResp.json();
            if (Array.isArray(list) && list.length > 0) release = list[0];
          }
        }
        const apkAsset = release && (release.assets || []).find((a) => a.name && a.name.endsWith(".apk"));
        if (apkAsset) {
          data = {
            ...data,
            version: data.version && data.version !== "pending-ci-build" ? data.version : (release.tag_name || "latest"),
            buildType: data.buildType && data.buildType !== "unbuilt-template" ? data.buildType : "github-release",
            releaseDate: apkAsset.updated_at || release.published_at || data.releaseDate,
            releaseDownloadUrl: apkAsset.browser_download_url,
            sizeBytes: apkAsset.size || data.sizeBytes
          };
        }
      } catch (_) {}
    }

    const releasesLinkEl = document.getElementById("releases-page-link");
    if (releasesLinkEl && data.repo) {
      releasesLinkEl.href = `https://github.com/${data.repo}/releases/latest`;
    }

    document.getElementById("version").textContent = data.version || "Pending CI";
    document.getElementById("release-date").textContent = data.releaseDate ? new Date(data.releaseDate).toLocaleString() : "—";
    document.getElementById("min-android").textContent = data.minAndroid || "Android 7.0 (API 24)";
    document.getElementById("sha256").textContent = data.sha256 || "Not built yet";

    if (data.sizeBytes && data.sizeBytes > 0) {
      const mb = (data.sizeBytes / (1024 * 1024)).toFixed(2);
      document.getElementById("apk-size").textContent = `${mb} MB`;

      // Always prefer GitHub Releases direct APK URL first (zero Netlify bandwidth usage)
      let resolvedDownloadUrl = null;
      if (data.releaseDownloadUrl && data.releaseDownloadUrl.startsWith("https://")) {
        resolvedDownloadUrl = data.releaseDownloadUrl;
      } else if (data.apkFile) {
        try {
          const head = await fetch(data.apkFile, { method: "HEAD" });
          if (head.ok) resolvedDownloadUrl = data.apkFile;
        } catch (_) {}
      }

      if (resolvedDownloadUrl) {
        btn.href = resolvedDownloadUrl;
        btn.classList.remove("disabled");
        btn.removeAttribute("aria-disabled");
        statusEl.textContent = `${data.buildType || "Ready"} • GitHub Release`;
      } else {
        statusEl.textContent = "APK binary not yet published";
      }
    } else {
      statusEl.textContent = data.repo
        ? `Waiting for GitHub Actions on ${data.repo}`
        : "Awaiting first CI build";
    }

    if (Array.isArray(data.changelog) && data.changelog.length > 0) {
      const ul = document.getElementById("changelog");
      ul.innerHTML = "";
      data.changelog.forEach((item) => {
        const li = document.createElement("li");
        li.textContent = item;
        ul.appendChild(li);
      });
    }
  } catch (err) {
    statusEl.textContent = "Run GitHub Actions to publish APK";
  }
}

loadReleaseMetadata();
