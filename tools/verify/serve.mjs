// Tiny static server for the before/after check. Run from the project root:  node tools/verify/serve.mjs
// Serves build/demo (written by DemoExportTest) plus tools/verify/compare.html.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../../build/demo");
const compare = path.join(here, "compare.html");
const types = { ".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8" };

http
  .createServer((req, res) => {
    const pathname = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    const file = pathname === "/compare.html" ? compare : path.join(root, pathname);
    if (file !== compare && !file.startsWith(root)) {
      res.writeHead(403).end();
      return;
    }
    fs.readFile(file, (err, data) => {
      if (err) {
        res.writeHead(404).end("not found: " + pathname);
        return;
      }
      res.writeHead(200, { "Content-Type": types[path.extname(file)] || "application/octet-stream", "Cache-Control": "no-store" });
      res.end(data);
    });
  })
  .listen(8765, () => console.log("Open http://localhost:8765/compare.html?page=index"));
