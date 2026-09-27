/* Node.js 20+. Run from project root: node tests/http-smoke.cjs.
   Starts both projects, checks Final 1.0 over HTTP and an offline backup/restore.
   All test data stays in a new temporary directory. */
const { spawn } = require("node:child_process");
const { createHmac } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, ".."),
  work = fs.mkdtempSync(path.join(os.tmpdir(), "skyshelf-http-"));
let backend,
  frontend,
  log = "",
  exitCode;
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
function start(cwd) {
  exitCode = undefined;
  log = "";
  backend = spawn(
    "java",
    ["-jar", path.join(root, "backend/target/skyshelf.jar"), "--app.demo=true"],
    { cwd },
  );
  backend.stdout.on("data", (b) => (log += b));
  backend.stderr.on("data", (b) => (log += b));
  backend.on("exit", (c) => (exitCode = c));
}
async function stop() {
  if (backend && exitCode === undefined)
    await new Promise((r) => {
      backend.once("exit", r);
      backend.kill("SIGTERM");
    });
}
async function ready() {
  for (let i = 0; i < 100; i++) {
    if (exitCode !== undefined)
      throw Error("Backend stopped: " + log.slice(-3000));
    try {
      if ((await fetch("http://127.0.0.1:5500/api/health")).ok) return;
    } catch {}
    await pause(200);
  }
  throw Error("Startup timeout");
}
function session() {
  const jar = new Map();
  let token;
  return {
    async req(p, method = "GET", body) {
      const headers = {
        Cookie: [...jar].map(([k, v]) => k + "=" + v).join("; "),
      };
      if (token && method !== "GET") headers[token.header] = token.token;
      if (body && !(body instanceof FormData)) {
        headers["Content-Type"] = "application/json";
        body = JSON.stringify(body);
      }
      const r = await fetch("http://127.0.0.1:5500/api" + p, {
        method,
        headers,
        body,
      });
      for (const c of r.headers.getSetCookie()) {
        const pair = c.split(";")[0],
          ix = pair.indexOf("=");
        jar.set(pair.slice(0, ix), pair.slice(ix + 1));
      }
      const b = Buffer.from(await r.arrayBuffer());
      let data;
      try {
        data = JSON.parse(b.toString());
      } catch {}
      if (p === "/auth/csrf") token = data;
      return { status: r.status, data, bytes: b };
    },
    async csrf() {
      await this.req("/auth/csrf");
    },
    async login(email, password, code) {
      await this.csrf();
      const r = await this.req("/auth/login", "POST", { email, password, code });
      assert.equal(r.status, 200, JSON.stringify(r.data));
      await this.csrf();
      return r.data;
    },
  };
}
async function upload(s, name, projectId, folderId, content = "SkyShelf bytes ✓") {
  const f = new FormData();
  f.append("file", new Blob([content]), name);
  if (projectId) f.append("projectId", projectId);
  if (folderId) f.append("folderId", folderId);
  const r = await s.req("/files", "POST", f);
  assert.equal(r.status, 201, JSON.stringify(r.data));
  return r.data;
}
function base32(value) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const ch of value.replace(/=/g, "").toUpperCase())
    bits += alphabet.indexOf(ch).toString(2).padStart(5, "0");
  return Buffer.from(
    [...bits.matchAll(/.{8}/g)].map((m) => Number.parseInt(m[0], 2)),
  );
}
function totp(secret, counter = Math.floor(Date.now() / 30000)) {
  const data = Buffer.alloc(8);
  data.writeBigUInt64BE(BigInt(counter));
  const hash = createHmac("sha1", base32(secret)).update(data).digest();
  const offset = hash.at(-1) & 15;
  const binary = hash.readUInt32BE(offset) & 0x7fffffff;
  return String(binary % 1_000_000).padStart(6, "0");
}
(async () => {
  start(work);
  frontend = spawn(
    "java",
    [
      path.join(root, "frontend/FrontendServer.java"),
      path.join(root, "frontend"),
      "5500",
    ],
    { cwd: root },
  );
  frontend.stderr.on("data", (b) => (log += b));
  await ready();
  const health = await fetch("http://127.0.0.1:5500/api/health");
  assert.equal(health.status, 200);
  assert.equal((await health.json()).version, "1.0.0");
  const home = await fetch("http://127.0.0.1:5500/");
  assert.equal(home.status, 200);
  assert.match(await home.text(), /app.js/);
  assert.match(home.headers.get("content-security-policy"), /frame-ancestors 'none'/);
  const designSystem = await fetch("http://127.0.0.1:5500/design-system.html");
  assert.equal(designSystem.status, 200);
  assert.match(await designSystem.text(), /UI inventory/);
  const a = session(),
    b = session();
  const pass = "TestPassword2026!";
  for (const [s, email, name] of [
    [a, "alice@example.com", "Alice"],
    [b, "bob@example.com", "Bob"],
  ]) {
    await s.csrf();
    assert.equal(
      (await s.req("/auth/register", "POST", { email, name, password: pass }))
        .status,
      201,
    );
    await s.login(email, pass);
  }
  const dir = (
    await a.req("/folders", "POST", { name: "Demo folder", color: "blue" })
  ).data;
  const f = await upload(a, "demo.txt", null, dir.id);
  assert.match(f.checksum, /^[a-f0-9]{64}$/);
  assert.equal(f.versionCount, 1);
  assert.equal(
    (await a.req(`/files/${f.id}/download`)).bytes.toString(),
    "SkyShelf bytes ✓",
  );
  assert.equal((await b.req(`/files/${f.id}/download`)).status, 404);
  const encryptedObjects = fs
    .readdirSync(path.join(work, "data/objects"))
    .map((name) => fs.readFileSync(path.join(work, "data/objects", name)));
  assert.ok(encryptedObjects.length > 0);
  assert.ok(encryptedObjects.every((bytes) => bytes.subarray(0, 4).toString() === "SKY2"));
  assert.ok(encryptedObjects.every((bytes) => !bytes.includes(Buffer.from("SkyShelf bytes"))));

  const next = new FormData();
  next.append("file", new Blob(["SkyShelf version two ✓"]), "demo.txt");
  next.append("note", "Версия для проверки");
  const versioned = await a.req(`/files/${f.id}/versions`, "POST", next);
  assert.equal(versioned.status, 201, JSON.stringify(versioned.data));
  assert.equal(versioned.data.versionCount, 2);
  let history = (await a.req(`/files/${f.id}/versions`)).data;
  assert.equal(history.length, 2);
  assert.equal(history[0].note, "Версия для проверки");
  assert.equal(
    (await a.req(`/files/${f.id}/download`)).bytes.toString(),
    "SkyShelf version two ✓",
  );
  assert.equal(
    (await a.req(`/files/${f.id}/versions/${history[1].id}/download`)).bytes.toString(),
    "SkyShelf bytes ✓",
  );
  const restoredVersion = await a.req(
    `/files/${f.id}/versions/${history[1].id}/restore`,
    "POST",
  );
  assert.equal(restoredVersion.status, 200);
  assert.equal(restoredVersion.data.versionCount, 3);
  assert.equal(
    (await a.req(`/files/${f.id}/download`)).bytes.toString(),
    "SkyShelf bytes ✓",
  );
  assert.equal(
    (await a.req(`/files/${f.id}`, "PATCH", { favorite: true })).data.favorite,
    true,
  );

  const pr = (
    await a.req("/projects", "POST", {
      name: "Team project",
      description: "Итоговая работа команды",
      course: "СПО",
      teacher: "Преподаватель",
      accent: "violet",
      cover: "aurora",
    })
  ).data;
  assert.equal(pr.tasks, 4);
  assert.equal(pr.course, "СПО");
  assert.equal(
    (
      await a.req(`/projects/${pr.id}/members`, "POST", {
        email: "bob@example.com",
        role: "READER",
      })
    ).status,
    200,
  );
  const shared = await upload(a, "shared.txt", pr.id, null, "Project final ✓");
  assert.equal((await b.req(`/files/${shared.id}/download`)).status, 200);
  assert.equal((await b.req(`/files/${shared.id}`, "DELETE")).status, 403);
  const tasks = (await a.req(`/projects/${pr.id}/tasks`)).data;
  assert.equal(tasks.length, 4);
  assert.equal(
    (
      await a.req(`/projects/${pr.id}/tasks/${tasks[0].id}`, "PATCH", {
        completed: true,
      })
    ).data[0].completed,
    true,
  );
  assert.equal(
    (await a.req(`/projects/${pr.id}/final/${shared.id}`, "POST")).data.status,
    "READY",
  );
  const submission = (
    await a.req(`/projects/${pr.id}/submissions`, "POST", { hours: 48 })
  ).data;
  const publicProject = await session().req(`/public/projects/${submission.token}`);
  assert.equal(publicProject.status, 200);
  assert.equal(publicProject.data.name, "Team project");
  assert.equal(publicProject.data.finalFile.checksum, shared.checksum);
  assert.equal(
    (await session().req(`/public/projects/${submission.token}/download`)).bytes.toString(),
    "Project final ✓",
  );
  const exported = await a.req(`/projects/${pr.id}/export`);
  assert.equal(exported.status, 200);
  assert.equal(exported.bytes.subarray(0, 2).toString(), "PK");

  const link = (
    await a.req(`/files/${shared.id}/share`, "POST", {
      hours: 24,
      password: "safe-link",
      maxDownloads: 1,
    })
  ).data;
  assert.notEqual(link.id, link.token);
  assert.equal(link.passwordRequired, true);
  const guest = session();
  assert.equal((await guest.req(`/public/${link.token}`)).data.remainingDownloads, 1);
  await guest.csrf();
  assert.equal(
    (await guest.req(`/public/${link.token}/download`, "POST", { password: "wrong" })).status,
    401,
  );
  assert.equal(
    (await guest.req(`/public/${link.token}/download`, "POST", { password: "safe-link" })).bytes.toString(),
    "Project final ✓",
  );
  assert.equal((await guest.req(`/public/${link.token}`)).status, 404);
  await a.req(`/files/${shared.id}/share`, "DELETE");
  assert.equal((await session().req(`/public/${link.token}`)).status, 404);

  const twoFactor = (await b.req("/security/2fa/setup", "POST")).data;
  assert.match(twoFactor.secret, /^[A-Z2-7]+$/);
  assert.equal(
    (await b.req("/security/2fa/enable", "POST", { code: totp(twoFactor.secret) })).status,
    200,
  );
  await b.req("/auth/logout", "POST");
  await b.csrf();
  assert.equal(
    (await b.req("/auth/login", "POST", { email: "bob@example.com", password: pass })).status,
    401,
  );
  await b.login("bob@example.com", pass, totp(twoFactor.secret));
  const security = (await b.req("/security/overview")).data;
  assert.equal(security.twoFactorEnabled, true);
  assert.equal(security.fileEncryption, "AES-256-GCM");
  assert.ok((await a.req("/activity")).data.length >= 10);
  assert.equal((await a.req(`/files/${f.id}`, "DELETE")).status, 200);
  assert.equal((await a.req(`/files/${f.id}/download`)).status, 404);
  assert.equal(
    (await a.req(`/files/${f.id}`, "PATCH", { trashed: false })).status,
    200,
  );
  const admin = session();
  await admin.login("admin@skyshelf.local", "SkyShelfAdmin2026!");
  assert.equal((await admin.req(`/files/${f.id}/download`)).status, 404);
  const me = (await b.req("/me")).data;
  assert.equal(
    (
      await admin.req(`/admin/users/${me.id}/plan`, "PATCH", {
        plan: "PERSONAL",
      })
    ).status,
    200,
  );
  assert.equal((await b.req("/storage")).data.quota, 100 * 1024 ** 3);
  // Check actual frontend handlers against the same running API when HAPPY_DOM_MODULE is set.
  if (process.env.HAPPY_DOM_MODULE) {
    const { Window } = await import(process.env.HAPPY_DOM_MODULE);
    const w = new Window({ url: "http://127.0.0.1:5500/#login" });
    w.document.write(
      fs
        .readFileSync(path.join(root, "frontend/index.html"), "utf8")
        .replace(/<script[^>]*><\/script>/, ""),
    );
    w.HTMLDialogElement.prototype.showModal = function () {
      this.open = true;
    };
    w.HTMLDialogElement.prototype.close = function () {
      this.open = false;
    };
    const jar = new Map();
    w.fetch = async (input, options = {}) => {
      const headers = {
        ...options.headers,
        Cookie: [...jar].map(([k, v]) => k + "=" + v).join("; "),
      };
      const r = await fetch(new URL(input, w.location.href), {
        ...options,
        headers,
      });
      for (const c of r.headers.getSetCookie()) {
        const x = c.split(";")[0],
          i = x.indexOf("=");
        jar.set(x.slice(0, i), x.slice(i + 1));
      }
      return r;
    };
    const errors = [];
    w.addEventListener("error", (e) => errors.push(e.message));
    w.eval(fs.readFileSync(path.join(root, "frontend/app.js"), "utf8"));
    async function wait(fn) {
      for (let i = 0; i < 100; i++) {
        if (fn()) return;
        await pause(50);
      }
      throw Error(
        "DOM wait timeout: " + w.document.body.textContent.slice(-2000),
      );
    }
    await wait(() => w.document.querySelector("#auth-form"));
    const form = w.document.querySelector("#auth-form");
    form.querySelector("[name=email]").value = "alice@example.com";
    form.querySelector("[name=password]").value = pass;
    form.dispatchEvent(
      new w.Event("submit", { bubbles: true, cancelable: true }),
    );
    await wait(() => w.document.querySelector(".page-heading"));
    assert.match(w.document.body.textContent, /demo.txt/);
    w.document.querySelector("[data-action=folder-new]").click();
    const folderForm = w.document.querySelector("#dialog-form");
    folderForm.querySelector("[name=name]").value = "Created in UI";
    folderForm.querySelector("[name=color]").value = "blue";
    folderForm.dispatchEvent(
      new w.Event("submit", { bubbles: true, cancelable: true }),
    );
    await wait(() =>
      w.document
        .querySelector(".folder-grid")
        ?.textContent.includes("Created in UI"),
    );
    const search = w.document.querySelector("#search");
    search.value = "missing-123";
    search.dispatchEvent(new w.Event("input"));
    assert.match(
      w.document.querySelector("#file-list").textContent,
      /Ничего не найдено/,
    );
    w.location.hash = "#projects";
    await wait(() => w.document.querySelector(".project-card"));
    assert.match(w.document.body.textContent, /Team project/);
    w.document.querySelector(".project-card").click();
    await wait(() => w.document.querySelector("[data-action=members]"));
    w.document.querySelector("[data-action=members]").click();
    await wait(() => w.document.querySelector(".member-row"));
    assert.equal(w.document.querySelectorAll(".member-row").length, 2);
    assert.deepEqual(errors, []);
    await w.happyDOM.close();
    console.log(
      "PASS: actual frontend JavaScript login, file list, folder creation, search, project navigation and members (DOM environment, no visual rendering).",
    );
  }
  await stop();
  const backup = path.join(work, "backup");
  fs.cpSync(path.join(work, "data"), backup, { recursive: true });
  const restored = path.join(work, "restored");
  fs.mkdirSync(restored);
  fs.cpSync(backup, path.join(restored, "data"), { recursive: true });
  start(restored);
  await ready();
  const c = session();
  await c.login("alice@example.com", pass);
  assert.equal(
    (await c.req(`/files/${f.id}/download`)).bytes.toString(),
    "SkyShelf bytes ✓",
  );
  assert.equal((await c.req("/projects")).data[0].name, "Team project");
  console.log(
    "PASS Final 1.0: proxy/CSP, CSRF sessions, encrypted objects, checksums, versions/restore, favorites, project checklist/final/submission/ZIP, protected one-use links, TOTP 2FA, audit, roles, trash, admin privacy, tariff and backup/restore.",
  );
})()
  .catch((e) => {
    console.error(e);
    console.error(log.slice(-4000));
    process.exitCode = 1;
  })
  .finally(async () => {
    await stop();
    frontend?.kill("SIGTERM");
  });
