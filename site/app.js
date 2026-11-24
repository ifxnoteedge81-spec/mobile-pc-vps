async function loadReleaseMetadata() {
  const btn = document.getElementById("download-btn");
  const statusEl = document.getElementById("build-status");
  try {
    const resp = await fetch("downloads/version.json", { cache: "no-store" });
    if (!resp.ok) throw new Error("Missing version.json");
    const data = await resp.json();

    document.getElementById("version").textContent = data.version || "Pending CI";
    document.getElementById("release-date").textContent = data.releaseDate ? new Date(data.releaseDate).toLocaleString() : "—";
    document.getElementById("min-android").textContent = data.minAndroid || "Android 7.0 (API 24)";
    document.getElementById("sha256").textContent = data.sha256 || "Not built yet";

    if (data.sizeBytes && data.sizeBytes > 0 && data.apkFile) {
      const mb = (data.sizeBytes / (1024 * 1024)).toFixed(2);
      document.getElementById("apk-size").textContent = `${mb} MB`;

      // Verify APK artifact actually exists before enabling download link
      const head = await fetch(data.apkFile, { method: "HEAD" });
      if (head.ok) {
        btn.href = data.apkFile;
        btn.classList.remove("disabled");
        btn.removeAttribute("aria-disabled");
        statusEl.textContent = data.buildType || "Ready";
      } else {
        statusEl.textContent = "APK binary not yet published";
      }
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
