package id.or.oo.hr.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GhcrRegistryTest {

    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private val routes = mutableMapOf<String, HttpResponse>()
    private val http = Http { url, headers ->
        requests += url to headers
        routes[url] ?: HttpResponse(404, emptyMap(), "{\"errors\":[{\"code\":\"NOT_FOUND\"}]}")
    }
    private val registry = GhcrRegistry(http)

    private val tokenUrl = "https://ghcr.io/token?scope=repository:oonid/hermes-agent:pull&service=ghcr.io"
    private val manifestUrl = "https://ghcr.io/v2/oonid/hermes-agent/manifests/gateway"
    private val configDigest = "sha256:da7c6ac2f2e2c3b56e097763024fbe7d4eaa58d7a4b6e7e6c0eb04771974cc5f"
    private val blobUrl = "https://ghcr.io/v2/oonid/hermes-agent/blobs/$configDigest"
    private val signedUrl = "https://pkg-containers.githubusercontent.com/ghcr1/blobs/sha256:da7c6a?se=2026"
    private val auth = mapOf("Authorization" to "Bearer t0ken")

    private fun json(body: String) = HttpResponse(200, emptyMap(), body)

    private fun manifest(config: String = configDigest) = """
        {"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json",
         "config":{"mediaType":"application/vnd.docker.container.image.v1+json","size":4383,"digest":"$config"},
         "layers":[{"mediaType":"application/vnd.docker.image.rootfs.diff.tar.gzip","size":4187659,
         "digest":"sha256:a9986cd6f37dbddae7862a6d4be71683472e7c2ea708e87db14f8a6393c00f00"}]}
    """.trimIndent()

    private fun config(variant: String = "gateway") = """
        {"architecture":"arm64","os":"linux","config":{"Env":["HERMES_HOME=/root/.hermes/profiles/android"],
         "Labels":{"io.github.oonid.hermes.patch-hash":"ab570a0b",
         "io.github.oonid.hermes.upstream-commit":"5b147ab036d6d19131bff8d20701b5001eab844a",
         "io.github.oonid.hermes.variant":"$variant",
         "org.opencontainers.image.revision":"819ce3dcb815dfe45506c4eb02ad0aafa16698d1",
         "org.opencontainers.image.version":"v0.22.3"}}}
    """.trimIndent()

    private fun serve(manifestBody: String = manifest(), configBody: String = config()) {
        routes[tokenUrl] = json("{\"token\":\"t0ken\"}")
        routes[manifestUrl] = json(manifestBody)
        routes[blobUrl] = HttpResponse(307, mapOf("location" to signedUrl), "")
        routes[signedUrl] = json(configBody)
    }

    @Test
    fun thePullTagResolvesToTheImmutableBuildTagFromTheLabels() {
        serve()

        assertEquals(ImageBuild("v0.22.3-pab570a0b-gateway", "v0.22.3"), registry.resolve("gateway"))
    }

    @Test
    fun theRegistryIsAskedAnonymouslyAndTheSignedBlobUrlWithoutTheToken() {
        serve()

        registry.resolve("gateway")

        assertEquals(listOf(tokenUrl, manifestUrl, blobUrl, signedUrl), requests.map { it.first })
        assertEquals(emptyMap<String, String>(), requests[0].second)
        assertEquals("Bearer t0ken", requests[1].second["Authorization"])
        assertEquals(
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json",
            requests[1].second["Accept"],
        )
        assertEquals(auth, requests[2].second)
        assertEquals(emptyMap<String, String>(), requests[3].second)
    }

    @Test
    fun anIndexIsNarrowedToTheLinuxArm64Image() {
        val arm64 = "sha256:" + "a".repeat(64)
        serve(
            manifestBody = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
                 {"digest":"sha256:${"b".repeat(64)}","platform":{"architecture":"amd64","os":"linux"}},
                 {"digest":"$arm64","platform":{"architecture":"arm64","os":"linux"}}]}
            """.trimIndent(),
        )
        routes["https://ghcr.io/v2/oonid/hermes-agent/manifests/$arm64"] = json(manifest())

        assertEquals(ImageBuild("v0.22.3-pab570a0b-gateway", "v0.22.3"), registry.resolve("gateway"))
        assertEquals("https://ghcr.io/v2/oonid/hermes-agent/manifests/$arm64", requests[2].first)
    }

    @Test
    fun anIndexWithoutArm64IsReported() {
        serve(
            manifestBody = """
                {"schemaVersion":2,"manifests":[{"digest":"sha256:${"b".repeat(64)}",
                 "platform":{"architecture":"amd64","os":"linux"}}]}
            """.trimIndent(),
        )

        val e = assertThrows(IllegalStateException::class.java) { registry.resolve("gateway") }

        assertEquals("ghcr.io/oonid/hermes-agent:gateway has no linux/arm64 image", e.message)
    }

    @Test
    fun theFullVariantHasNoSuffix() {
        serve(configBody = config(variant = "full"))

        assertEquals(ImageBuild("v0.22.3-pab570a0b", "v0.22.3"), registry.resolve("gateway"))
    }

    @Test
    fun anImageWithoutBuildLabelsIsReported() {
        serve(configBody = """{"architecture":"arm64","os":"linux","config":{}}""")

        val e = assertThrows(IllegalStateException::class.java) { registry.resolve("gateway") }

        assertEquals("ghcr.io/oonid/hermes-agent:gateway has no build labels", e.message)
    }

    @Test
    fun anHttpErrorNamesTheStepAndTheStatus() {
        serve()
        routes.remove(manifestUrl)

        val e = assertThrows(IllegalStateException::class.java) { registry.resolve("gateway") }

        assertEquals("ghcr.io/oonid/hermes-agent:gateway: manifest gateway: HTTP 404", e.message)
    }
}
