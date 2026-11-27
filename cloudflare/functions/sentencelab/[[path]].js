const ORIGIN = "https://jsaur.duckdns.org";
const routes = new Map([
  ["/sentencelab/health", ["GET"]],
  ["/sentencelab/v1/auth/challenge", ["POST"]],
  ["/sentencelab/v1/auth/google", ["POST"]],
  ["/sentencelab/v1/auth/logout", ["POST"]],
  ["/sentencelab/v1/me", ["GET"]],
  ["/sentencelab/v1/snapshot", ["GET", "PUT"]],
  ["/sentencelab/v1/library", ["GET"]],
  ["/sentencelab/v1/sets/copy", ["POST"]],
  ["/sentencelab/v1/account", ["DELETE"]]
]);

function error(status, detail, extra = {}) {
  return Response.json({ detail }, {
    status, headers: { "Cache-Control": "no-store", ...extra }
  });
}

export async function onRequest({ request }) {
  const url = new URL(request.url);
  const methods = routes.get(url.pathname);
  if (!methods) return error(404, "Not found");
  if (!methods.includes(request.method)) {
    return error(405, "Method not allowed", { Allow: methods.join(", ") });
  }

  const headers = new Headers({ Accept: "application/json" });
  for (const name of ["Authorization", "Content-Type"]) {
    const value = request.headers.get(name);
    if (value !== null) headers.set(name, value);
  }
  try {
    const response = await fetch(ORIGIN + url.pathname + url.search, {
      method: request.method,
      headers,
      body: request.method === "GET" ? undefined : request.body,
      redirect: "manual"
    });
    if (response.status >= 300 && response.status < 400) {
      return error(502, "Unexpected upstream redirect");
    }
    const resultHeaders = new Headers({
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      "Content-Type": response.headers.get("Content-Type") || "application/json"
    });
    return new Response(response.body, { status: response.status, headers: resultHeaders });
  } catch {
    return error(502, "SentenceLab upstream unavailable");
  }
}
