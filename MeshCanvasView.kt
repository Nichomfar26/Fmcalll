package com.fmcall.serval.ui.nodes

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.fmcall.serval.data.model.MeshNode
import com.fmcall.serval.data.model.SignalQuality
import kotlin.math.*

/**
 * Custom View that draws an animated mesh network topology.
 * Shows nodes as circles with connecting lines, pulsing animations,
 * and signal quality colors.
 */
class MeshCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val paintNode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(13, 0, 200, 83)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val GREEN   = Color.parseColor("#00C853")
    private val YELLOW  = Color.parseColor("#FFD600")
    private val RED     = Color.parseColor("#FF3B3B")
    private val SURFACE = Color.parseColor("#1A1A1A")

    data class NodeData(
        val label: String,
        var x: Float,
        var y: Float,
        val color: Int,
        val size: Float,
        val isLocal: Boolean = false,
        val quality: SignalQuality = SignalQuality.GOOD
    )

    private var nodes = listOf<NodeData>()
    private var edges = listOf<Pair<Int, Int>>()
    private var pulseRadius = 0f
    private var pulseAlpha = 255
    private var animPhase = 0f

    private val runnable = object : Runnable {
        override fun run() {
            animPhase += 0.05f
            pulseRadius = (sin(animPhase) * 15f + 20f)
            pulseAlpha = ((sin(animPhase) * 0.4f + 0.6f) * 255).toInt()
            invalidate()
            postDelayed(this, 50)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(runnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(runnable)
    }

    fun setMeshNodes(meshNodes: List<MeshNode>, localMeshId: String) {
        if (width == 0 || height == 0) {
            post { setMeshNodes(meshNodes, localMeshId) }
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.65f

        val nodeList = mutableListOf<NodeData>()
        // Local node in center
        nodeList.add(NodeData("Vous", cx, cy, GREEN, 12f, isLocal = true))

        // Other nodes arranged in a circle
        val others = meshNodes.take(8)
        others.forEachIndexed { i, node ->
            val angle = (2 * PI * i / others.size) - PI / 2
            val nx = cx + r * cos(angle).toFloat()
            val ny = cy + r * sin(angle).toFloat()
            val color = when (node.signalQuality) {
                SignalQuality.EXCELLENT -> GREEN
                SignalQuality.GOOD      -> Color.parseColor("#7BC853")
                SignalQuality.FAIR      -> YELLOW
                SignalQuality.WEAK      -> RED
            }
            nodeList.add(NodeData(
                node.displayName.take(2).uppercase(),
                nx, ny, color, 8f,
                quality = node.signalQuality
            ))
        }

        // Edges: connect all to center + some inter-node
        val edgeList = mutableListOf<Pair<Int, Int>>()
        for (i in 1 until nodeList.size) edgeList.add(0 to i)
        if (nodeList.size > 3) {
            edgeList.add(1 to 2)
            edgeList.add(2 to 3)
        }
        if (nodeList.size > 5) edgeList.add(4 to 5)

        nodes = nodeList
        edges = edgeList
        invalidate()
    }

    fun setDemoNodes() {
        if (width == 0 || height == 0) {
            post { setDemoNodes() }
            return
        }
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.65f

        val labels = listOf("HB", "FZ", "OA", "KM", "📡", "N6")
        val colors = listOf(GREEN, Color.parseColor("#AA60FF"),
            Color.parseColor("#60A0FF"), RED, YELLOW, Color.GRAY)

        val n = mutableListOf(NodeData("Vous", cx, cy, GREEN, 12f, true))
        labels.forEachIndexed { i, label ->
            val angle = (2 * PI * i / labels.size) - PI / 2
            n.add(NodeData(label,
                cx + r * cos(angle).toFloat(),
                cy + r * sin(angle).toFloat(),
                colors[i], 8f))
        }
        nodes = n
        edges = listOf(0 to 1, 0 to 2, 0 to 3, 0 to 4, 0 to 5, 1 to 6, 2 to 4, 3 to 5)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawGrid(canvas)
        drawEdges(canvas)
        drawNodes(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#111111"))

        // Radial glow from center
        if (nodes.isNotEmpty()) {
            val center = nodes[0]
            val shader = RadialGradient(
                center.x, center.y, width * 0.6f,
                intArrayOf(Color.argb(30, 0, 200, 83), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            paintGlow.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintGlow)
            paintGlow.shader = null
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 30f
        var x = 0f
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), paintGrid); x += step }
        var y = 0f
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, paintGrid); y += step }
    }

    private fun drawEdges(canvas: Canvas) {
        edges.forEach { (a, b) ->
            if (a >= nodes.size || b >= nodes.size) return@forEach
            val na = nodes[a]; val nb = nodes[b]
            val grad = LinearGradient(
                na.x, na.y, nb.x, nb.y,
                intArrayOf(Color.argb(180, 0, 200, 83), Color.argb(40, 0, 200, 83)),
                null, Shader.TileMode.CLAMP
            )
            paintLine.shader = grad
            paintLine.strokeWidth = 1.5f
            canvas.drawLine(na.x, na.y, nb.x, nb.y, paintLine)
            paintLine.shader = null

            // Animated packet dot on edge
            val progress = (animPhase / (2 * PI)).toFloat() % 1f
            val dotX = na.x + (nb.x - na.x) * progress
            val dotY = na.y + (nb.y - na.y) * progress
            paintNode.color = Color.argb(180, 0, 200, 83)
            canvas.drawCircle(dotX, dotY, 3f, paintNode)
        }
    }

    private fun drawNodes(canvas: Canvas) {
        nodes.forEach { node ->
            // Glow
            val glowShader = RadialGradient(
                node.x, node.y, node.size * 3f,
                intArrayOf(Color.argb(80, Color.red(node.color),
                    Color.green(node.color), Color.blue(node.color)),
                    Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            paintGlow.shader = glowShader
            canvas.drawCircle(node.x, node.y, node.size * 3f, paintGlow)
            paintGlow.shader = null

            // Pulse ring for local node
            if (node.isLocal) {
                paintLine.shader = null
                paintLine.color = Color.argb(pulseAlpha / 2, 0, 200, 83)
                paintLine.strokeWidth = 1f
                canvas.drawCircle(node.x, node.y, node.size + pulseRadius, paintLine)
                canvas.drawCircle(node.x, node.y, node.size + pulseRadius * 1.8f, paintLine)
            }

            // Node circle
            paintNode.color = node.color
            canvas.drawCircle(node.x, node.y, node.size, paintNode)

            // White border
            paintLine.shader = null
            paintLine.color = Color.argb(60, 255, 255, 255)
            paintLine.strokeWidth = 1.5f
            canvas.drawCircle(node.x, node.y, node.size, paintLine)

            // Label
            paintText.textSize = if (node.isLocal) 20f else 18f
            paintText.color = Color.WHITE
            val labelY = node.y + node.size + 18f
            canvas.drawText(node.label, node.x, labelY, paintText)
        }
    }
}
