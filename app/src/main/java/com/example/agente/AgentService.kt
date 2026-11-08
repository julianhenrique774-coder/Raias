package com.example.agente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class UiElement(
    val id: Int,
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
    val cx: Int,
    val cy: Int
)

class AgentService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentService? = null
        private const val MAX_STEPS = 30
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var overlay: Button? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopTask()
    }

    override fun onDestroy() {
        stopTask()
        instance = null
        super.onDestroy()
    }

    // ---------- controle da tarefa ----------

    fun startTask(goal: String, apiKey: String, model: String) {
        stopTask()
        showOverlay()
        job = scope.launch {
            try {
                runLoop(goal, apiKey, model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Erro: ${e.message}")
            } finally {
                hideOverlay()
            }
        }
    }

    fun stopTask() {
        job?.cancel()
        job = null
        hideOverlay()
    }

    // ---------- loop do agente (observar -> pensar -> agir) ----------

    private suspend fun runLoop(goal: String, apiKey: String, model: String) {
        val history = mutableListOf<String>()
        for (step in 1..MAX_STEPS) {
            delay(1200)
            val pkg = rootInActiveWindow?.packageName?.toString() ?: "desconhecido"
            val els = dump()

            val d = withContext(Dispatchers.IO) {
                GeminiClient.nextStep(apiKey, model, goal, pkg, els, history)
            }
            history.add("$step. ${d.action} ${d.id ?: ""} ${d.text ?: d.app ?: ""} (${d.thought})".replace("  ", " "))

            when (d.action) {
                "CLICK" -> {
                    val e = els.firstOrNull { it.id == d.id }
                    if (e != null) tap(e.cx.toFloat(), e.cy.toFloat())
                    else history.add("(id inválido, escolha um id da lista)")
                }
                "TYPE" -> typeText(d.text ?: "")
                "ENTER" -> pressEnter()
                "SCROLL_DOWN" -> swipe(true)
                "SCROLL_UP" -> swipe(false)
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "OPEN_APP" -> openApp(d.app ?: "")
                "WAIT" -> delay(2000)
                "FINISH" -> {
                    toast(d.thought.ifEmpty { "Concluído" })
                    return
                }
            }
        }
        toast("Limite de passos atingido")
    }

    // ---------- leitura da tela ----------

    private fun dump(): List<UiElement> {
        val out = mutableListOf<UiElement>()
        rootInActiveWindow?.let { walk(it, out) }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<UiElement>) {
        if (n == null || out.size >= 80) return
        if (n.isVisibleToUser) {
            val label = (n.text ?: n.contentDescription)?.toString() ?: ""
            if (n.isClickable || n.isEditable || label.isNotEmpty()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    out.add(UiElement(out.size, label.take(80), n.isClickable, n.isEditable, r.centerX(), r.centerY()))
                }
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }

    // ---------- ações ----------

    private suspend fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        gesture(p, 60)
    }

    private suspend fun swipe(down: Boolean) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val p = Path().apply {
            moveTo(w / 2, if (down) h * 0.7f else h * 0.3f)
            lineTo(w / 2, if (down) h * 0.3f else h * 0.7f)
        }
        gesture(p, 350)
    }

    private suspend fun gesture(p: Path, dur: Long) = suspendCancellableCoroutine<Unit> { c ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (c.isActive) c.resume(Unit)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (c.isActive) c.resume(Unit)
            }
        }, null)
        if (!ok && c.isActive) c.resume(Unit)
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            findEditable(n.getChild(i))?.let { return it }
        }
        return null
    }

    private fun typeText(t: String) {
        val node = focusedInput() ?: return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun pressEnter() {
        if (Build.VERSION.SDK_INT >= 30) {
            focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
        if (match == null) {
            toast("App não encontrado: $name")
            return
        }
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------- botão flutuante PARAR ----------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "PARAR"
            setOnClickListener { stopTask() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 200
        }
        wm.addView(b, lp)
        overlay = b
    }

    private fun hideOverlay() {
        overlay?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }
}
