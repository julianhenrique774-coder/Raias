package com.example.agente

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Decision(
    val thought: String,
    val action: String,
    val id: Int?,
    val text: String?,
    val app: String?
)

object GeminiClient {

    fun nextStep(
        apiKey: String,
        model: String,
        goal: String,
        pkg: String,
        elements: List<UiElement>,
        history: List<String>
    ): Decision {
        val screen = JSONArray()
        elements.forEach {
            screen.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("clickable", it.clickable)
                    .put("editable", it.editable)
            )
        }

        val prompt = """
Você é um agente autônomo que controla um celular Android.
Objetivo do usuário: "$goal"

App atual na tela: $pkg
Passos já executados:
${if (history.isEmpty()) "(nenhum)" else history.takeLast(12).joinToString("\n")}

ELEMENTOS VISÍVEIS NA TELA:
$screen

Ações possíveis:
- OPEN_APP (campo "app": nome do app, ex: "WhatsApp")
- CLICK (campo "id": id do elemento)
- TYPE (campo "text": texto a digitar no campo de texto ativo)
- ENTER (pressiona Enter/Pesquisar no teclado)
- SCROLL_DOWN, SCROLL_UP, BACK, HOME, WAIT
- FINISH (objetivo concluído, ou impossível/arriscado; explique em "thought")

Regras:
- Dê UM passo por vez. Não repita a mesma ação se ela não surtiu efeito; tente outra abordagem.
- NUNCA confirme compras, pagamentos, transferências, exclusões ou envio de dados sensíveis: use FINISH e explique o que falta confirmar.
- Se o objetivo já estiver cumprido, use FINISH.

Responda APENAS com JSON neste formato:
{"thought":"raciocínio curto","action":"CLICK","id":0,"text":null,"app":null}
""".trimIndent()

        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.2)
            )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("API $code: ${resp.take(200)}")

        val out = JSONObject(resp)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0)
            .getString("text")

        val j = JSONObject(
            out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        )

        fun str(k: String): String? =
            if (j.isNull(k)) null else j.optString(k).takeIf { it.isNotEmpty() && it != "null" }

        return Decision(
            thought = j.optString("thought", ""),
            action = j.optString("action", "WAIT").uppercase(),
            id = if (j.isNull("id")) null else j.optInt("id", -1).takeIf { it >= 0 },
            text = str("text"),
            app = str("app")
        )
    }
}
