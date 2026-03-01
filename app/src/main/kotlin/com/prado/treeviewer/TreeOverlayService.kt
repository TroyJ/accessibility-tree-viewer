package com.prado.treeviewer

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TreeOverlayService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var logView: TextView? = null
    private var logContainer: LinearLayout? = null
    private var buttonBar: LinearLayout? = null
    private var yellowIndicator: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRefresh: Runnable? = null

    private val allLines = mutableListOf<String>()
    private var scrollOffset = 0
    private var visibleLineCount = 40

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Right-side block overlay
    private val blockWindows = mutableListOf<View>()
    private var rightSideVisible = true
    private var screenWidth = 0
    private var screenHeight = 0

    // ── Cached tree ──────────────────────────────────────────────────

    /** Data-only snapshot for equality comparison. data class gives structural equals(). */
    private data class CachedNode(
        val className: String,
        val text: String?,
        val contentDescription: String?,
        val viewId: String?,
        val isClickable: Boolean,
        val isLongClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val children: List<CachedNode>
    )

    /** Live tree node pairing cached data with the AccessibilityNodeInfo reference. */
    private class TreeNode(
        val data: CachedNode,
        val nodeInfo: AccessibilityNodeInfo,
        val children: List<TreeNode>
    )

    private var lastCachedTree: CachedNode? = null

    private data class BlockInfo(
        val label: String,
        val clickNode: AccessibilityNodeInfo?,
        val lclickNode: AccessibilityNodeInfo?,
        val multiClick: Boolean,
        val multiLClick: Boolean
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager!!.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        createLogOverlay()
        createButtonBar()
        createYellowIndicator()

        // Log version at startup
        allLines.add("=== Tree Viewer ${BuildConfig.VERSION_DISPLAY} ===")
        allLines.add("--- Service connected ${timeFormat.format(Date())} ---")
        updateLogDisplay()

        // Trigger initial tree scan so blocks appear immediately
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Ignore events from our own overlay
        if (event.packageName == "com.prado.treeviewer") return
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            scheduleRefresh()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        pendingRefresh?.let { handler.removeCallbacks(it) }
        removeAllBlockWindows()
        yellowIndicator?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        logContainer?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        buttonBar?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        logContainer = null
        buttonBar = null
        logView = null
        yellowIndicator = null
        lastCachedTree = null
    }

    // ── Log overlay (left half) ─────────────────────────────────────

    private fun createLogOverlay() {
        val wm = windowManager ?: return
        val halfWidth = screenWidth / 2

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(8, 48, 8, 8)
        }

        val legend = TextView(this).apply {
            text = "[CLICK]=clickable  [EDIT]=editable  [SCROLL]=scrollable  [L-CLICK]=long-clickable"
            setTextColor(Color.argb(200, 180, 180, 180))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, 8)
        }
        container.addView(legend)

        val tv = TextView(this).apply {
            setTextColor(Color.argb(230, 0, 255, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f)
            typeface = Typeface.MONOSPACE
            text = "Waiting for accessibility events..."
        }
        container.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            halfWidth,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }

        wm.addView(container, params)
        logContainer = container
        logView = tv
    }

    // ── Button bar (top-left, touchable) ────────────────────────────

    private fun createButtonBar() {
        val wm = windowManager ?: return
        val dp = resources.displayMetrics.density

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 40, 40, 40))
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
        }

        val btnStyle: (TextView, String) -> Unit = { btn, label ->
            btn.text = label
            btn.setTextColor(Color.WHITE)
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.setBackgroundColor(Color.argb(200, 80, 80, 80))
            btn.setPadding(
                (12 * dp).toInt(), (6 * dp).toInt(),
                (12 * dp).toInt(), (6 * dp).toInt()
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)
            bar.addView(btn, lp)
        }

        val btnPgUp = TextView(this)
        btnStyle(btnPgUp, "\u25B2 PgU")
        btnPgUp.setOnClickListener { pageUp() }
        btnPgUp.setOnLongClickListener { scrollOffset = 0; updateLogDisplay(); true }

        val btnPgDn = TextView(this)
        btnStyle(btnPgDn, "\u25BC PgD")
        btnPgDn.setOnClickListener { pageDown() }
        btnPgDn.setOnLongClickListener {
            scrollOffset = (allLines.size - visibleLineCount).coerceAtLeast(0)
            updateLogDisplay()
            true
        }

        val btnToggle = TextView(this)
        btnStyle(btnToggle, "\u25A0 R")
        btnToggle.setOnClickListener { toggleRightSide(btnToggle) }

        val btnClose = TextView(this)
        btnStyle(btnClose, "\u2716 X")
        btnClose.setBackgroundColor(Color.argb(200, 180, 40, 40))
        btnClose.setOnClickListener { shutdown() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }

        wm.addView(bar, params)
        buttonBar = bar
    }

    // ── Yellow indicator (bottom-right, visual only) ────────────────

    private fun createYellowIndicator() {
        val wm = windowManager ?: return
        val size = screenWidth / 16

        val indicator = View(this).apply {
            setBackgroundColor(Color.argb(120, 255, 255, 0))
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 0
            y = 0
        }

        wm.addView(indicator, params)
        yellowIndicator = indicator
        if (!rightSideVisible) indicator.visibility = View.GONE
    }

    // ── Toggle right side ───────────────────────────────────────────

    private fun toggleRightSide(btn: TextView) {
        rightSideVisible = !rightSideVisible
        if (rightSideVisible) {
            btn.text = "\u25A0 R"
            yellowIndicator?.visibility = View.VISIBLE
            scheduleRefresh()
        } else {
            btn.text = "\u25A1 R"
            removeAllBlockWindows()
            yellowIndicator?.visibility = View.GONE
        }
    }

    // ── Refresh logic ───────────────────────────────────────────────

    private fun scheduleRefresh() {
        pendingRefresh?.let { handler.removeCallbacks(it) }
        val r = Runnable { refreshTree() }
        pendingRefresh = r
        handler.postDelayed(r, 500)
    }

    /**
     * Single tree walk builds a TreeNode (with CachedNode data + live AccessibilityNodeInfo refs).
     * Compares CachedNode with previous snapshot — skips all updates if unchanged.
     */
    private fun refreshTree() {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) {
            allLines.add("--- ${timeFormat.format(Date())} --- (no root window)")
            updateLogDisplay()
            return
        }

        // Single walk: build TreeNode tree
        val tree = buildTree(root)

        // Compare with cache — skip if unchanged
        if (tree.data == lastCachedTree) return
        lastCachedTree = tree.data

        // Tree changed — update log
        val lines = mutableListOf<String>()
        lines.add("--- ${timeFormat.format(Date())} ---")
        val pkg = root.packageName?.toString() ?: "unknown"
        lines.add("\u2500\u2500 ${root.className?.shortName()} [$pkg]")
        traverseForLog(tree, lines, "   ")

        allLines.addAll(lines)
        if (allLines.size > 3000) {
            val excess = allLines.size - 3000
            repeat(excess) { allLines.removeAt(0) }
            scrollOffset = (scrollOffset - excess).coerceAtLeast(0)
        }
        scrollOffset = (allLines.size - visibleLineCount).coerceAtLeast(0)
        updateLogDisplay()

        // Update blocks (only if right side visible)
        if (rightSideVisible) {
            val blocks = mutableListOf<BlockInfo>()
            val consumed = mutableSetOf<TreeNode>()
            scanForBlocks(tree, blocks, consumed)
            updateBlockOverlays(blocks)
        }
    }

    // ── Tree building ────────────────────────────────────────────────

    /** Recursively builds a TreeNode from an AccessibilityNodeInfo. */
    private fun buildTree(node: AccessibilityNodeInfo): TreeNode {
        val childNodes = mutableListOf<TreeNode>()
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            childNodes.add(buildTree(child))
        }

        val data = CachedNode(
            className = node.className?.toString() ?: "",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            children = childNodes.map { it.data }
        )

        return TreeNode(data, node, childNodes)
    }

    // ── Log traversal (from TreeNode) ────────────────────────────────

    private fun traverseForLog(
        node: TreeNode,
        lines: MutableList<String>,
        prefix: String
    ) {
        val childCount = node.children.size
        for (i in 0 until childCount) {
            val child = node.children[i]

            val last = i == childCount - 1
            val connector = if (last) "\u2514\u2500\u2500 " else "\u251C\u2500\u2500 "
            val childPrefix = if (last) "$prefix    " else "$prefix\u2502   "

            val d = child.data
            val sb = StringBuilder()
            sb.append(prefix).append(connector)
            sb.append(d.className.substringAfterLast(".").ifEmpty { "?" })

            d.text?.let { text ->
                val t = if (text.length > 50) text.substring(0, 50) + "..." else text
                sb.append(" \"$t\"")
            }
            d.contentDescription?.let { desc ->
                val dd = if (desc.length > 40) desc.substring(0, 40) + "..." else desc
                sb.append(" cd=\"$dd\"")
            }

            val tags = mutableListOf<String>()
            if (d.isClickable) tags.add("[CLICK]")
            if (d.isEditable) tags.add("[EDIT]")
            if (d.isScrollable) tags.add("[SCROLL]")
            if (d.isLongClickable) tags.add("[L-CLICK]")
            if (tags.isNotEmpty()) sb.append(" ").append(tags.joinToString(" "))

            d.viewId?.let { id ->
                sb.append(" #").append(id.substringAfter("/"))
            }

            lines.add(sb.toString())

            if (child.children.isNotEmpty()) {
                traverseForLog(child, lines, childPrefix)
            }
        }
    }

    // ── Block scanning (deepest-first, post-order, from TreeNode) ───

    /**
     * Post-order DFS on TreeNode tree.
     * Deepest clickable/long-clickable nodes get blocks first.
     * Once consumed, excluded from higher-level block calculations.
     *
     * Mitigations:
     * - Self-clickable leaf nodes get their own block
     * - collectText skips consumed subtrees so labels are scoped correctly
     */
    private fun scanForBlocks(
        node: TreeNode,
        blocks: MutableList<BlockInfo>,
        consumed: MutableSet<TreeNode>
    ) {
        // Post-order: recurse into all children first
        for (child in node.children) {
            scanForBlocks(child, blocks, consumed)
        }

        // Check this node's children for un-consumed clickable/long-clickable
        val clickChildren = node.children.filter { it.data.isClickable && it !in consumed }
        val lclickChildren = node.children.filter { it.data.isLongClickable && it !in consumed }

        if (clickChildren.isNotEmpty() || lclickChildren.isNotEmpty()) {
            val label = collectText(node, consumed)
            if (label.isNotBlank()) {
                blocks.add(BlockInfo(
                    label = label,
                    clickNode = clickChildren.firstOrNull()?.nodeInfo,
                    lclickNode = lclickChildren.firstOrNull()?.nodeInfo,
                    multiClick = clickChildren.size > 1,
                    multiLClick = lclickChildren.size > 1
                ))
            }
            // Mark all children of this parent as consumed
            for (child in node.children) {
                consumed.add(child)
            }
        } else if (node !in consumed &&
            (node.data.isClickable || node.data.isLongClickable) &&
            node.children.none { it.data.isClickable || it.data.isLongClickable }
        ) {
            // Self-clickable leaf/terminal node
            val label = collectText(node, consumed)
            if (label.isNotBlank()) {
                blocks.add(BlockInfo(
                    label = label,
                    clickNode = if (node.data.isClickable) node.nodeInfo else null,
                    lclickNode = if (node.data.isLongClickable) node.nodeInfo else null,
                    multiClick = false,
                    multiLClick = false
                ))
            }
            consumed.add(node)
        }
    }

    /**
     * DFS to collect text from TreeNode subtree.
     * Skips consumed subtrees so labels are scoped to the current block.
     * Format: "<resourceId> text" per entry, joined with " | "
     */
    private fun collectText(node: TreeNode, consumed: Set<TreeNode>): String {
        val parts = mutableListOf<String>()
        collectTextDfs(node, parts, consumed)
        return parts.joinToString(" | ")
    }

    private fun collectTextDfs(node: TreeNode, parts: MutableList<String>, consumed: Set<TreeNode>) {
        val d = node.data
        val isTextView = d.className.contains("TextView") || d.className.contains("EditText")

        if (isTextView && d.text != null) {
            val idPart = d.viewId?.substringAfter("/")
            if (idPart != null) {
                parts.add("<$idPart> ${d.text}")
            } else {
                parts.add(d.text)
            }
        }

        // Fallback: non-TextView leaf with contentDescription
        if (!isTextView && node.children.isEmpty() && d.contentDescription != null) {
            val idPart = d.viewId?.substringAfter("/")
            if (idPart != null) {
                parts.add("<$idPart> ${d.contentDescription}")
            } else {
                parts.add(d.contentDescription)
            }
        }

        for (child in node.children) {
            if (child in consumed) continue
            collectTextDfs(child, parts, consumed)
        }
    }

    // ── Block overlay management ────────────────────────────────────

    private fun updateBlockOverlays(blocks: List<BlockInfo>) {
        removeAllBlockWindows()

        val wm = windowManager ?: return
        val blockWidth = screenWidth / 8
        val blockHeight = screenHeight / 10
        val rightStart = screenWidth / 2
        val maxCols = 4
        val maxRows = 10
        val maxBlocks = maxCols * maxRows

        val visibleBlocks = blocks.take(maxBlocks)

        for ((index, block) in visibleBlocks.withIndex()) {
            val col = index % maxCols
            val row = index / maxCols
            if (row >= maxRows) break

            val x = rightStart + (col * blockWidth)
            val y = row * blockHeight

            val blockView = createBlockView(block, blockWidth, blockHeight)

            val params = WindowManager.LayoutParams(
                blockWidth, blockHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                this.x = x
                this.y = y
            }

            wm.addView(blockView, params)
            blockWindows.add(blockView)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun createBlockView(block: BlockInfo, width: Int, height: Int): View {
        val halfWidth = width / 2

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // Left half — click action
        val leftColor = when {
            block.clickNode == null -> Color.argb(100, 128, 128, 128) // grey
            block.multiClick -> Color.argb(100, 200, 40, 40)          // red
            else -> Color.argb(100, 40, 180, 40)                      // green
        }
        val leftHalf = LinearLayout(this).apply {
            setBackgroundColor(leftColor)
            gravity = Gravity.CENTER
        }
        if (block.clickNode != null) {
            val node = block.clickNode
            leftHalf.setOnClickListener {
                try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
            }
        }

        // Right half — long-click action
        val rightColor = when {
            block.lclickNode == null -> Color.argb(100, 128, 128, 128) // grey
            block.multiLClick -> Color.argb(100, 200, 40, 40)          // red
            else -> Color.argb(100, 40, 80, 200)                       // blue
        }
        val rightHalf = LinearLayout(this).apply {
            setBackgroundColor(rightColor)
            gravity = Gravity.CENTER
        }
        if (block.lclickNode != null) {
            val node = block.lclickNode
            rightHalf.setOnClickListener {
                try { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } catch (_: Exception) {}
            }
        }

        container.addView(leftHalf, LinearLayout.LayoutParams(halfWidth, LinearLayout.LayoutParams.MATCH_PARENT))
        container.addView(rightHalf, LinearLayout.LayoutParams(halfWidth, LinearLayout.LayoutParams.MATCH_PARENT))

        // Label text spans both halves — overlay on top
        val label = TextView(this).apply {
            text = block.label
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            maxLines = 3
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 6, 12, 1, TypedValue.COMPLEX_UNIT_SP
            )
        }

        // Use a FrameLayout to overlay the label on top of the two halves
        val frame = android.widget.FrameLayout(this)
        frame.addView(container, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // Label is NOT clickable — touches pass through it to the halves underneath
        label.isClickable = false
        label.isFocusable = false
        frame.addView(label, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))

        return frame
    }

    private fun removeAllBlockWindows() {
        val wm = windowManager ?: return
        for (view in blockWindows) {
            try { wm.removeView(view) } catch (_: Exception) {}
        }
        blockWindows.clear()
    }

    // ── Log pagination ──────────────────────────────────────────────

    private fun pageUp() {
        scrollOffset = (scrollOffset - visibleLineCount).coerceAtLeast(0)
        updateLogDisplay()
    }

    private fun pageDown() {
        scrollOffset = (scrollOffset + visibleLineCount).coerceAtMost(
            (allLines.size - visibleLineCount).coerceAtLeast(0)
        )
        updateLogDisplay()
    }

    private fun updateLogDisplay() {
        val tv = logView ?: return
        // Pin version string as first line (always visible)
        val versionLine = if (allLines.isNotEmpty() && allLines[0].startsWith("===")) allLines[0] else null
        val end = (scrollOffset + visibleLineCount).coerceAtMost(allLines.size)
        val start = scrollOffset.coerceIn(0, end)
        val page = allLines.subList(start, end).joinToString("\n")
        val header = "[${start + 1}-$end of ${allLines.size}]\n"
        tv.text = if (versionLine != null && start > 0) {
            "$versionLine\n$header$page"
        } else {
            "$header$page"
        }
    }

    // ── Shutdown ────────────────────────────────────────────────────

    private fun shutdown() {
        pendingRefresh?.let { handler.removeCallbacks(it) }
        removeAllBlockWindows()
        yellowIndicator?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        logContainer?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        buttonBar?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        logContainer = null
        buttonBar = null
        logView = null
        yellowIndicator = null
        lastCachedTree = null
        disableSelf()
    }

    // ── Utility ─────────────────────────────────────────────────────

    private fun CharSequence.shortName(): String {
        return this.toString().substringAfterLast(".")
    }
}
