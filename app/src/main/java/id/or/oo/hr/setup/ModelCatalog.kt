package id.or.oo.hr.setup

import kotlinx.coroutines.CancellationException
import org.json.JSONArray

const val CODEX_MODELS_SCRIPT =
    "V=\$(ls -d /root/.hermes/installs/*/environments/*/venv | head -1); " +
        "\"\$V/bin/python3\" -c 'import json,sys; sys.path.insert(0,\"/root/.hermes/hermes-agent\"); " +
        "from hermes_cli.models import _codex_catalog; " +
        "print(\"MODELS \" + json.dumps(list(_codex_catalog(\"openai-codex\", True))))'"

private const val MODELS_PREFIX = "MODELS "

class ModelCatalog(private val command: HermesCommand) {

    suspend fun codexModels(): List<String>? {
        var json: String? = null
        val exit = try {
            command.run(CODEX_MODELS_SCRIPT) { line ->
                if (json == null && line.startsWith(MODELS_PREFIX)) json = line.removePrefix(MODELS_PREFIX)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val text = json
        return if (exit == 0 && text != null) parse(text) else null
    }

    private fun parse(text: String): List<String>? = try {
        val array = JSONArray(text)
        (0 until array.length()).map { index -> array.get(index) as? String ?: return null }
            .takeIf { it.isNotEmpty() }
    } catch (e: org.json.JSONException) {
        null
    }
}
