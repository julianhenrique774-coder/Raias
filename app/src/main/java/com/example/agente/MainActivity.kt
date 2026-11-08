package com.example.agente

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            setPadding(0, pad, 0, 0)
        }

        status = TextView(this)

        val btnAccess = Button(this).apply {
            text = "1. Ativar serviço de acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val key = EditText(this).apply {
            hint = "Chave da API Gemini"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val model = EditText(this).apply {
            setText(prefs.getString("model", "gemini-2.5-flash"))
            setSingleLine()
        }
        val goal = EditText(this).apply {
            hint = "Ex.: Abra o WhatsApp e mande 'oi' para Mãe"
            minLines = 3
        }

        val btnStart = Button(this).apply {
            text = "2. Iniciar agente"
            setOnClickListener {
                val svc = AgentService.instance
                val k = key.text.toString().trim()
                val g = goal.text.toString().trim()
                when {
                    svc == null -> toast("Ative o serviço de acessibilidade primeiro")
                    k.isEmpty() -> toast("Informe a chave da API")
                    g.isEmpty() -> toast("Descreva o objetivo")
                    else -> {
                        prefs.edit()
                            .putString("key", k)
                            .putString("model", model.text.toString().trim())
                            .apply()
                        svc.startTask(g, k, model.text.toString().trim())
                        moveTaskToBack(true)
                    }
                }
            }
        }

        col.addView(status)
        col.addView(btnAccess)
        col.addView(label("Chave da API (grátis em aistudio.google.com/apikey)"))
        col.addView(key)
        col.addView(label("Modelo"))
        col.addView(model)
        col.addView(label("O que o agente deve fazer?"))
        col.addView(goal)
        col.addView(btnStart)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (AgentService.instance != null) "Serviço: ATIVO" else "Serviço: desativado"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
