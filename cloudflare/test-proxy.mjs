import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
const source = await readFile(new URL("./functions/sentencelab/[[path]].js", import.meta.url), "utf8");
const { onRequest } = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));
let calls = [];
globalThis.fetch = async (url, options) => {
  calls.push({url, options});
  return new Response(JSON.stringify({ok:true}), {status:200,headers:{"Content-Type":"application/json","Set-Cookie":"must-not-forward=1"}});
};
const request = (path, init) => onRequest({request:new Request("https://sentencelab.pages.dev"+path,init)});
let res = await request("/sentencelab/health");
assert.equal(res.status,200);
assert.deepEqual(await res.json(),{ok:true});
assert.equal(res.headers.get("Cache-Control"),"no-store");
assert.equal(res.headers.has("Set-Cookie"),false);
res = await request("/sentencelab/v1/auth/google",{method:"POST",headers:{"Content-Type":"application/json",Authorization:"Bearer fake-test-token",Cookie:"not-forwarded"},body:'{"test":true}'});
assert.equal(calls.at(-1).url,"https://jsaur.duckdns.org/sentencelab/v1/auth/google");
assert.equal(calls.at(-1).options.headers.get("Authorization"),"Bearer fake-test-token");
assert.equal(calls.at(-1).options.headers.has("Cookie"),false);
assert.equal(await new Response(calls.at(-1).options.body).text(),'{"test":true}');
assert.equal(calls.at(-1).options.redirect,"manual");
assert.equal((await request("/sentencelab/v1/snapshot",{method:"POST"})).status,405);
const count=calls.length;
for (const path of ["/sentencelab/unknown","/chessmine/api/cross/me","/blender-mcp/ws","/sentencelab/v1/auth/google/"]) {
  assert.equal((await request(path)).status,404);
}
assert.equal(calls.length,count);
globalThis.fetch = async () => new Response(null,{status:302,headers:{Location:"https://example.com"}});
assert.equal((await request("/sentencelab/health")).status,502);
globalThis.fetch = async () => {throw Error("network");};
assert.equal((await request("/sentencelab/health")).status,502);
console.log("Proxy tests passed.");
