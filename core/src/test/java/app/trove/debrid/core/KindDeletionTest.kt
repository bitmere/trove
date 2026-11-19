package app.trove.debrid.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KindDeletionTest {
    @Test fun `known kind deletes without searching overlapping ID namespaces`() = runBlocking {
        for ((kind, path, field) in listOf(
            Triple(DownloadKind.TORRENT, "torrents/controltorrent", "torrent_id"),
            Triple(DownloadKind.WEB, "webdl/controlwebdownload", "webdl_id"),
            Triple(DownloadKind.USENET, "usenet/controlusenetdownload", "usenetdownload_id"),
        )) {
            val requests = mutableListOf<HttpRequest>()
            val transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse {
                    requests += request
                    return HttpResponse(200, """{"success":true,"data":true}""")
                }
            }
            val provider = TorBoxProvider("test-key", transport)
            val download = DebridDownload("11", DebridService.TORBOX, "File", 100, DownloadStatus.READY, 1f, kind = kind)
            assertTrue(provider.delete(download).isSuccess)
            val adapter: DebridProvider = provider
            assertTrue(adapter.delete(DownloadRef(download.kind, download.id)).isSuccess)
            assertEquals(2, requests.size)
            assertEquals(requests.first().url, requests.last().url)
            assertEquals(requests.first().jsonBody, requests.last().jsonBody)
            val request = requests.first()
            assertEquals("${TorBoxProvider.BASE}/$path", request.url)
            assertEquals("""{"operation":"delete","$field":11}""", request.jsonBody)
        }
    }

    @Test fun `a real 401 on the optional Usenet list still rejects the key`() = runBlocking {
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest) =
                if (request.url.contains("/usenet/")) HttpResponse(401, "{}")
                else HttpResponse(200, """{"success":true,"data":[]}""")
        }
        assertTrue(TorBoxProvider("test-key", transport).listDownloads().exceptionOrNull() is DebridError.AuthInvalid)
    }
}
