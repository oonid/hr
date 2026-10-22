package id.or.oo.hr.image

import org.json.JSONObject
import java.net.URL

data class ImageBuild(val tag: String, val version: String)

fun interface ImageRegistry {
    fun resolve(tag: String): ImageBuild
}

class GhcrRegistry(
    private val http: Http,
    private val host: String = "ghcr.io",
    private val repository: String = "oonid/hermes-agent",
) : ImageRegistry {

    override fun resolve(tag: String): ImageBuild {
        val image = "$host/$repository:$tag"
        val token = JSONObject(fetch(image, "token", tokenUrl(), emptyMap()).body).getString("token")
        val auth = mapOf("Authorization" to "Bearer $token")
        var manifest = JSONObject(fetch(image, "manifest $tag", manifestUrl(tag), auth + ACCEPT).body)
        val entries = manifest.optJSONArray("manifests")
        if (entries != null) {
            val arm64 = (0 until entries.length()).map(entries::getJSONObject).firstOrNull { entry ->
                val platform = entry.optJSONObject("platform")
                platform?.optString("os") == "linux" && platform.optString("architecture") == "arm64"
            } ?: error("$image has no linux/arm64 image")
            val digest = arm64.getString("digest")
            manifest = JSONObject(fetch(image, "manifest $digest", manifestUrl(digest), auth + ACCEPT).body)
        }
        val configDigest = manifest.getJSONObject("config").getString("digest")
        val config = JSONObject(blob(image, configDigest, auth).body)
        val labels = config.optJSONObject("config")?.optJSONObject("Labels")
        val version = labels?.optString(VERSION).orEmpty()
        val patchHash = labels?.optString(PATCH_HASH).orEmpty()
        val variant = labels?.optString(VARIANT).orEmpty()
        check(version.isNotEmpty() && patchHash.isNotEmpty() && variant.isNotEmpty()) { "$image has no build labels" }
        val suffix = if (variant == "full") "" else "-$variant"
        return ImageBuild("$version-p$patchHash$suffix", version)
    }

    private fun blob(image: String, digest: String, auth: Map<String, String>): HttpResponse {
        val url = "https://$host/v2/$repository/blobs/$digest"
        val response = http.get(url, auth)
        if (response.code in 300..399) {
            val location = response.headers["location"] ?: error("$image: config $digest: redirect without a location")
            return fetch(image, "config $digest", URL(URL(url), location).toString(), emptyMap())
        }
        return checked(image, "config $digest", response)
    }

    private fun fetch(image: String, step: String, url: String, headers: Map<String, String>): HttpResponse =
        checked(image, step, http.get(url, headers))

    private fun checked(image: String, step: String, response: HttpResponse): HttpResponse {
        check(response.code == 200) { "$image: $step: HTTP ${response.code}" }
        return response
    }

    private fun tokenUrl() = "https://$host/token?scope=repository:$repository:pull&service=$host"

    private fun manifestUrl(reference: String) = "https://$host/v2/$repository/manifests/$reference"

    private companion object {
        const val VERSION = "org.opencontainers.image.version"
        const val PATCH_HASH = "io.github.oonid.hermes.patch-hash"
        const val VARIANT = "io.github.oonid.hermes.variant"
        val ACCEPT = mapOf(
            "Accept" to "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json",
        )
    }
}
