/* Run from project root. Optional: npm install --no-save playwright; npx playwright install chromium.
   node tests/browser-smoke.cjs. Uses an isolated temp database and launches both servers. */
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const assert = require("node:assert/strict");
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = path.resolve(__dirname, ".."),
  work = fs.mkdtempSync(path.join(os.tmpdir(), "skyshelf-browser-"));
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
let backend,
  frontend,
  browser,
  logs = "",
  lastBackendExit;
function startBackend() {
  backend = spawn(
    "java",
    ["-jar", path.join(root, "backend/target/skyshelf.jar"), "--app.demo=true"],
    { cwd: work },
  );
  backend.stdout.on("data", (b) => (logs += b));
  backend.stderr.on("data", (b) => (logs += b));
  backend.on("exit", (c) => (lastBackendExit = c));
}
async function ready() {
  for (let i = 0; i < 100; i++) {
    if (lastBackendExit !== undefined)
      throw new Error("Backend stopped: " + logs.slice(-4000));
    try {
      if (
        (await fetch("http://127.0.0.1:5500/api/health")).ok &&
        logs.includes("Started Application")
      )
        return;
    } catch {}
    await pause(200);
  }
  throw new Error("Startup timeout: " + logs.slice(-3000));
}
async function login(page, email, password) {
  await page.goto("http://127.0.0.1:5500/#login");
  await page.locator("[name=email]").fill(email);
  await page.locator("[name=password]").fill(password);
  await page.locator("#auth-form button[type=submit]").click();
  await page.waitForSelector(".page-heading h1");
}
(async () => {
  startBackend();
  frontend = spawn(
    "java",
    [
      path.join(root, "frontend/FrontendServer.java"),
      path.join(root, "frontend"),
      "5500",
    ],
    { cwd: root },
  );
  frontend.stderr.on("data", (b) => (logs += b));
  await ready();
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROME_PATH || undefined,
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });
  const context = await browser.newContext({
      viewport: { width: 1440, height: 960 },
    }),
    page = await context.newPage(),
    errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const screenshots = path.join(root, "docs/screenshots");
  fs.mkdirSync(screenshots, { recursive: true });
  await page.goto("http://127.0.0.1:5500/#home");
  await page.waitForSelector(".hero-copy");
  await page.screenshot({
    path: path.join(screenshots, "01-home.png"),
    fullPage: true,
  });
  await login(page, "demo@skyshelf.local", "SkyShelfDemo2026!");
  await page.waitForSelector("tbody tr");
  await page.screenshot({
    path: path.join(screenshots, "02-files.png"),
    fullPage: true,
  });
  await page.locator("[data-action=folder-new]").click();
  await page.locator("#dialog-form [name=name]").fill("Проверка Final");
  await page.locator("#dialog-form button[type=submit]").click();
  await page.waitForSelector("dialog:not([open])");
  await page.getByRole("button", { name: "Проверка Final", exact: true }).click();
  await page
    .locator("#upload-input")
    .setInputFiles({
      name: "check.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("SkyShelf exact bytes ✓"),
    });
  await page.getByText("check.txt", { exact: true }).waitFor();
  const downloadWait = page.waitForEvent("download");
  await page.getByRole("link", { name: "Скачать check.txt" }).click();
  const download = await downloadWait;
  assert.equal(
    fs.readFileSync(await download.path(), "utf8"),
    "SkyShelf exact bytes ✓",
  );
  await page.locator("[data-action=file-edit]").click();
  await page.locator("#dialog-form [name=name]").fill("ready.txt");
  await page.locator("#dialog-form button[type=submit]").click();
  await page.getByText("ready.txt", { exact: true }).waitFor();
  await page.locator("[data-action=share]").click();
  await page.locator("#share-form button[type=submit]").click();
  await page.locator("#share-url").waitFor();
  const link = await page.locator("#share-url").inputValue();
  const guestContext = await browser.newContext(),
    guest = await guestContext.newPage();
  await guest.goto(link);
  await guest.getByRole("link", { name: "Скачать файл" }).waitFor();
  await page.locator("[data-action=revoke]").click();
  await page.waitForFunction(() => !document.querySelector("#share-url"));
  await guest.reload();
  await guest.getByText("Ссылка недоступна", { exact: true }).waitFor();
  await guestContext.close();
  await page.locator("[data-action=modal-close]").click();
  await page.locator("[data-action=file-edit]").click();
  await page.locator("[data-action=trash-file]").click();
  await page.waitForSelector("dialog:not([open])");
  await page.locator('a[href="#trash"]').click();
  await page.locator("[data-action=restore]").click();
  await page.getByText("Корзина пуста", { exact: true }).waitFor();
  await page.locator('a[href="#projects"]').first().click();
  await page
    .getByRole("heading", { name: "SkyShelf · СПО", exact: true })
    .waitFor();
  await page.locator(".project-card").first().click();
  await page.getByText("Задание команды.txt", { exact: true }).waitFor();
  await page.locator("[data-action=members]").click();
  await page.getByRole("heading", { name: "Участники проекта" }).waitFor();
  assert.equal(await page.locator(".member-row").count(), 3);
  await page.screenshot({
    path: path.join(screenshots, "03-team.png"),
    fullPage: true,
  });
  await page.locator("[data-action=modal-close]").click();
  await page.locator("[data-action=file-edit]").click();
  await page.locator("[data-action=trash-file]").click();
  await page.waitForSelector("dialog:not([open])");
  await page.locator("[data-action=project-trash]").click();
  await page.locator("[data-action=restore]").click();
  await page.getByText("Корзина пуста", { exact: true }).waitFor();
  await page.locator('a[href="#settings"]').first().click();
  await page.locator("#settings-form [name=accent]").selectOption("violet");
  await page.locator("#settings-form button[type=submit]").click();
  await page.waitForFunction(
    () => document.documentElement.dataset.accent === "violet",
  );
  await page.reload();
  await page.waitForSelector("#settings-form");
  assert.equal(
    await page.locator("html").getAttribute("data-accent"),
    "violet",
  );
  await page.locator("[data-action=logout]").click();
  await page.waitForSelector(".hero-copy");
  await login(page, "reader@skyshelf.local", "SkyShelfDemo2026!");
  await page.locator('a[href="#projects"]').first().click();
  await page.locator(".project-card").first().click();
  await page.getByText("Задание команды.txt", { exact: true }).waitFor();
  assert.equal(await page.locator("[data-action=upload]").count(), 0);
  assert.equal(await page.locator("[data-action=file-edit]").count(), 0);
  await page.locator("[data-action=logout]").click();
  await page.waitForSelector(".hero-copy");
  await login(page, "admin@skyshelf.local", "SkyShelfAdmin2026!");
  await page.locator('a[href="#admin"]').click();
  await page.waitForSelector(".admin-table");
  await page.screenshot({
    path: path.join(screenshots, "04-admin.png"),
    fullPage: true,
  });
  const mobile = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    deviceScaleFactor: 1,
  });
  const mp = await mobile.newPage();
  await login(mp, "demo@skyshelf.local", "SkyShelfDemo2026!");
  await mp.waitForSelector("tbody tr");
  assert.equal(
    await mp.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth,
    ),
    false,
  );
  await mp.screenshot({
    path: path.join(screenshots, "05-mobile.png"),
    fullPage: true,
  });
  await mobile.close();
  // Restart the Java backend with the same data directory, then check persisted file bytes.
  await new Promise((resolve) => {
    backend.once("exit", resolve);
    backend.kill("SIGTERM");
  });
  lastBackendExit = undefined;
  logs = "";
  startBackend();
  await ready();
  await login(page, "demo@skyshelf.local", "SkyShelfDemo2026!");
  await page.getByText("ready.txt", { exact: true }).waitFor();
  assert.deepEqual(errors, []);
  console.log(
    "PASS: browser login, folder, upload, exact download, rename, sharing/revoke, trash/restore, project roles, theme persistence, admin, mobile, backend restart persistence.",
  );
})()
  .catch((e) => {
    console.error(e);
    console.error(logs.slice(-3000));
    process.exitCode = 1;
  })
  .finally(async () => {
    if (browser) await browser.close();
    backend?.kill("SIGTERM");
    frontend?.kill("SIGTERM");
  });
