package app.trove.debrid.core

/**
 * Shared fake [HttpTransport] for `:core` provider tests. Records every
 * request it receives and answers through the test-supplied handler, so each
 * provider test keeps its own handler lambdas and assertions; a failure still
 * names the provider test class, not this helper.
 *
 * The per-provider response-body helpers (`ok` / `success` / `upload` /
 * `envelope`) are intentionally NOT shared: each wraps a different provider
 * envelope (AllDebrid `{"status":"success","data":…}`, Debrid-Link
 * `{"success":true,"value":…}`, Premiumize `{"status":"success",…}`,
 * Offcloud raw body, TorBox `{"success":true,"data":…}`), so one shared
 * helper would change the bytes under test.
 */
class RecordingTransport(
    private val handler: (HttpRequest) -> HttpResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }
}
