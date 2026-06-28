package expo.modules.t3reviewdiff

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class T3ReviewDiffView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val horizontalScrollView = HorizontalScrollView(context)
  private val scrollView = ScrollView(context)
  private val contentView = ReviewDiffContentView(context)
  private var appearanceScheme = "light"
  private var themePayload: JSONObject? = null
  private var stylePayload: JSONObject? = null
  private var rowHeightOverride: Double? = null
  private var contentWidthOverride: Double? = null
  private var tokensResetKey = ""
  private var lastMetricsDebugKey = ""
  private var lastVisibleRangeDebugKey = ""
  private var initialRowIndex: Int? = null
  private var hasAppliedInitialRowIndex = false

  private val onDebug by EventDispatcher()
  private val onToggleFile by EventDispatcher()
  private val onToggleViewedFile by EventDispatcher()
  private val onPressLine by EventDispatcher()
  private val onToggleComment by EventDispatcher()

  init {
    clipToOutline = true
    horizontalScrollView.isFillViewport = true
    horizontalScrollView.isHorizontalScrollBarEnabled = false
    scrollView.isFillViewport = true
    scrollView.isVerticalScrollBarEnabled = true

    contentView.onToggleFile = { fileId -> onToggleFile(mapOf("fileId" to fileId)) }
    contentView.onToggleViewedFile = { fileId -> onToggleViewedFile(mapOf("fileId" to fileId)) }
    contentView.onPressLine = { payload -> onPressLine(payload) }
    contentView.onToggleComment = { commentId -> onToggleComment(mapOf("commentId" to commentId)) }
    contentView.onDrawMetrics = { metrics -> emitDebug("draw-metrics", metrics) }
    contentView.onContentMetricsChanged = { updateContentLayout() }

    scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
      contentView.verticalOffset = scrollY.toFloat()
      contentView.invalidate()
      emitVisibleRange("scroll")
    }
    horizontalScrollView.setOnScrollChangeListener { _, scrollX, _, _, _ ->
      contentView.horizontalOffset = scrollX.toFloat()
      contentView.invalidate()
    }

    scrollView.addView(contentView, FrameLayout.LayoutParams(1, 1))
    horizontalScrollView.addView(
      scrollView,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
    addView(
      horizontalScrollView,
      LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    applyTheme()
    applyStyle()
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    contentView.viewportWidth = width.toFloat()
    contentView.viewportHeight = height.toFloat()
    updateContentLayout()
    emitVisibleRange("layout")
  }

  fun setRowsJson(rowsJson: String) {
    try {
      val rows = parseRows(rowsJson)
      contentView.rows = rows
      hasAppliedInitialRowIndex = false
      emitDebug(
        "rows-decoded",
        mapOf("rows" to rows.size, "firstKind" to (rows.firstOrNull()?.kind ?: "none")),
      )
    } catch (error: JSONException) {
      contentView.rows = emptyList()
      hasAppliedInitialRowIndex = false
      emitDebug("rows-decode-failed", mapOf("error" to error.message.orEmpty()))
    }
    updateContentLayout()
  }

  fun setTokensJson(tokensJson: String) {
    try {
      contentView.tokensByRowId = parseTokensByRowId(JSONObject(tokensJson))
    } catch (error: JSONException) {
      contentView.tokensByRowId = emptyMap()
      emitDebug("tokens-decode-failed", mapOf("error" to error.message.orEmpty()))
    }
  }

  fun setTokensPatchJson(tokensPatchJson: String) {
    try {
      applyTokensPatch(JSONObject(tokensPatchJson))
    } catch (error: JSONException) {
      emitDebug("tokens-patch-decode-failed", mapOf("error" to error.message.orEmpty()))
    }
  }

  fun setTokensResetKey(nextTokensResetKey: String) {
    if (nextTokensResetKey == tokensResetKey) return
    tokensResetKey = nextTokensResetKey
    contentView.tokensByRowId = emptyMap()
    emitDebug("tokens-reset", mapOf("resetKey" to nextTokensResetKey))
  }

  fun setCollapsedFileIdsJson(collapsedFileIdsJson: String) {
    contentView.collapsedFileIds = decodeStringSet(collapsedFileIdsJson)
    updateContentLayout()
  }

  fun setViewedFileIdsJson(viewedFileIdsJson: String) {
    contentView.viewedFileIds = decodeStringSet(viewedFileIdsJson)
  }

  fun setSelectedRowIdsJson(selectedRowIdsJson: String) {
    contentView.selectedRowIds = decodeStringSet(selectedRowIdsJson)
  }

  fun setCollapsedCommentIdsJson(collapsedCommentIdsJson: String) {
    contentView.collapsedCommentIds = decodeStringSet(collapsedCommentIdsJson)
    updateContentLayout()
  }

  fun setAppearanceScheme(nextAppearanceScheme: String) {
    appearanceScheme = nextAppearanceScheme
    applyTheme()
  }

  fun setThemeJson(themeJson: String) {
    themePayload = parseObjectOrNull(themeJson, "theme-decode-failed")
    applyTheme()
  }

  fun setStyleJson(styleJson: String) {
    stylePayload = parseObjectOrNull(styleJson, "style-decode-failed")
    applyStyle()
  }

  fun setRowHeight(rowHeight: Double) {
    rowHeightOverride = rowHeight.takeIf { it.isFinite() && it > 0 }
    applyStyle()
  }

  fun setContentWidth(contentWidth: Double) {
    contentWidthOverride = contentWidth.takeIf { it.isFinite() && it > 0 }
    applyStyle()
  }

  fun setInitialRowIndex(initialRowIndex: Double) {
    this.initialRowIndex = initialRowIndex
      .takeIf { it.isFinite() && it >= 0 }
      ?.let { floor(it).toInt() }
    hasAppliedInitialRowIndex = false
    applyInitialRowIndexIfNeeded()
  }

  private fun applyTokensPatch(patch: JSONObject) {
    val resetKey = patch.optStringOrNull("resetKey")
    if (resetKey != null && resetKey != tokensResetKey) {
      tokensResetKey = resetKey
      contentView.tokensByRowId = emptyMap()
    }

    val patchTokens = patch.optJSONObject("tokensByRowId")?.let(::parseTokensByRowId).orEmpty()
    if (patchTokens.isEmpty()) return
    contentView.mergeTokensByRowId(patchTokens)

    val chunkIndex = patch.optNullableInt("chunkIndex")
    if (chunkIndex != null && (chunkIndex < 5 || chunkIndex % 10 == 0)) {
      emitDebug(
        "tokens-patch-decoded",
        mapOf("chunkIndex" to chunkIndex, "rows" to patchTokens.size, "totalRows" to contentView.tokenRowCount),
      )
    }
  }

  private fun decodeStringSet(json: String): Set<String> =
    try {
      val array = JSONArray(json)
      buildSet {
        for (index in 0 until array.length()) {
          array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
        }
      }
    } catch (error: JSONException) {
      emitDebug("file-id-set-decode-failed", mapOf("error" to error.message.orEmpty()))
      emptySet()
    }

  private fun parseObjectOrNull(json: String, failureMessage: String): JSONObject? =
    try {
      JSONObject(json)
    } catch (error: JSONException) {
      emitDebug(failureMessage, mapOf("error" to error.message.orEmpty()))
      null
    }

  private fun applyTheme() {
    contentView.theme = ReviewDiffNativeTheme.resolve(appearanceScheme, themePayload)
    setBackgroundColor(contentView.theme.background)
    scrollView.setBackgroundColor(contentView.theme.background)
    horizontalScrollView.setBackgroundColor(contentView.theme.background)
  }

  private fun applyStyle() {
    contentView.style = ReviewDiffNativeStyle.resolve(
      context,
      stylePayload,
      rowHeightOverride,
      contentWidthOverride,
    )
    updateContentLayout()
  }

  private fun updateContentLayout() {
    contentView.viewportWidth = width.toFloat()
    contentView.viewportHeight = height.toFloat()
    contentView.horizontalOffset = horizontalScrollView.scrollX.toFloat()
    contentView.verticalOffset = scrollView.scrollY.toFloat()

    val targetWidth = max(max(width, 1), ceil(contentView.contentPixelWidth).toInt())
    val targetHeight = max(max(height, 1), ceil(contentView.contentHeight).toInt())
    updateLayoutSize(scrollView, targetWidth, ViewGroup.LayoutParams.MATCH_PARENT)
    updateLayoutSize(contentView, targetWidth, targetHeight)
    emitMetrics(targetWidth, targetHeight)
    applyInitialRowIndexIfNeeded()
  }

  private fun updateLayoutSize(view: View, targetWidth: Int, targetHeight: Int) {
    val params = view.layoutParams ?: FrameLayout.LayoutParams(targetWidth, targetHeight)
    if (params.width == targetWidth && params.height == targetHeight) return
    params.width = targetWidth
    params.height = targetHeight
    view.layoutParams = params
  }

  private fun emitMetrics(targetWidth: Int, targetHeight: Int) {
    val debugKey = "${contentView.rowCount}:$width:$height:$targetWidth:$targetHeight"
    if (debugKey == lastMetricsDebugKey) return
    lastMetricsDebugKey = debugKey
    emitDebug(
      "metrics",
      mapOf(
        "rows" to contentView.rowCount,
        "boundsWidth" to width,
        "boundsHeight" to height,
        "contentHeight" to targetHeight,
        "contentWidth" to targetWidth,
        "fileHeaderHeight" to contentView.style.fileHeaderHeight,
        "rowHeight" to contentView.style.rowHeight,
      ),
    )
  }

  private fun applyInitialRowIndexIfNeeded() {
    val rowIndex = initialRowIndex ?: return
    if (hasAppliedInitialRowIndex || height <= 0) return
    val rowFrame = contentView.frameForRow(rowIndex) ?: return
    val targetScreenY = max(0f, (height - rowFrame.height()) * 0.3f)
    val maxOffset = max(contentView.contentHeight - height, 0f)
    val targetOffset = min(max(rowFrame.top - targetScreenY, 0f), maxOffset).roundToInt()
    hasAppliedInitialRowIndex = true
    scrollView.post {
      scrollView.scrollTo(0, targetOffset)
      contentView.verticalOffset = scrollView.scrollY.toFloat()
      emitVisibleRange("initial-row")
    }
  }

  private fun emitVisibleRange(reason: String) {
    val range = contentView.currentVisibleRowRange() ?: return
    val debugKey = "${range.first}:${range.last}:$height"
    if (debugKey == lastVisibleRangeDebugKey) return
    lastVisibleRangeDebugKey = debugKey
    emitDebug(
      "visible-range",
      mapOf(
        "reason" to reason,
        "firstRowIndex" to range.first,
        "lastRowIndex" to range.last,
        "totalRows" to contentView.rowCount,
      ),
    )
  }

  private fun emitDebug(message: String, details: Map<String, Any>) {
    onDebug(details + mapOf("message" to message))
  }
}

private data class ReviewDiffNativeRow(
  val kind: String,
  val id: String,
  val fileId: String?,
  val filePath: String?,
  val previousPath: String?,
  val changeType: String?,
  val additions: Int?,
  val deletions: Int?,
  val text: String?,
  val content: String?,
  val change: String?,
  val oldLineNumber: Int?,
  val newLineNumber: Int?,
  val wordDiffRanges: List<ReviewDiffNativeWordDiffRange>,
  val commentText: String?,
  val commentRangeLabel: String?,
  val commentSectionTitle: String?,
)

private data class ReviewDiffNativeWordDiffRange(val start: Int, val end: Int)

private data class ReviewDiffNativeToken(
  val content: String,
  val color: Int?,
  val fontStyle: Int?,
)

private data class ReviewDiffNativeTheme(
  val background: Int,
  val text: Int,
  val mutedText: Int,
  val headerBackground: Int,
  val border: Int,
  val hunkBackground: Int,
  val hunkText: Int,
  val addBackground: Int,
  val deleteBackground: Int,
  val addBar: Int,
  val deleteBar: Int,
  val addText: Int,
  val deleteText: Int,
) {
  companion object {
    fun resolve(scheme: String, payload: JSONObject?): ReviewDiffNativeTheme {
      val fallback = fallback(scheme)
      return ReviewDiffNativeTheme(
        background = parseColor(payload?.optStringOrNull("background"), fallback.background),
        text = parseColor(payload?.optStringOrNull("text"), fallback.text),
        mutedText = parseColor(payload?.optStringOrNull("mutedText"), fallback.mutedText),
        headerBackground = parseColor(
          payload?.optStringOrNull("headerBackground"),
          fallback.headerBackground,
        ),
        border = parseColor(payload?.optStringOrNull("border"), fallback.border),
        hunkBackground = parseColor(payload?.optStringOrNull("hunkBackground"), fallback.hunkBackground),
        hunkText = parseColor(payload?.optStringOrNull("hunkText"), fallback.hunkText),
        addBackground = parseColor(payload?.optStringOrNull("addBackground"), fallback.addBackground),
        deleteBackground = parseColor(payload?.optStringOrNull("deleteBackground"), fallback.deleteBackground),
        addBar = parseColor(payload?.optStringOrNull("addBar"), fallback.addBar),
        deleteBar = parseColor(payload?.optStringOrNull("deleteBar"), fallback.deleteBar),
        addText = parseColor(payload?.optStringOrNull("addText"), fallback.addText),
        deleteText = parseColor(payload?.optStringOrNull("deleteText"), fallback.deleteText),
      )
    }

    private fun fallback(scheme: String): ReviewDiffNativeTheme =
      if (scheme == "dark") {
        ReviewDiffNativeTheme(
          background = Color.rgb(18, 18, 18),
          text = Color.rgb(230, 230, 230),
          mutedText = Color.rgb(132, 132, 138),
          headerBackground = Color.rgb(26, 26, 26),
          border = Color.rgb(41, 41, 41),
          hunkBackground = Color.rgb(7, 31, 40),
          hunkText = Color.rgb(104, 205, 242),
          addBackground = Color.rgb(13, 47, 40),
          deleteBackground = Color.rgb(57, 20, 21),
          addBar = Color.rgb(0, 202, 177),
          deleteBar = Color.rgb(255, 103, 98),
          addText = Color.rgb(94, 204, 113),
          deleteText = Color.rgb(255, 103, 98),
        )
      } else {
        ReviewDiffNativeTheme(
          background = Color.WHITE,
          text = Color.rgb(7, 7, 7),
          mutedText = Color.rgb(120, 120, 128),
          headerBackground = Color.WHITE,
          border = Color.rgb(224, 224, 230),
          hunkBackground = Color.rgb(224, 242, 255),
          hunkText = Color.rgb(0, 115, 189),
          addBackground = Color.rgb(229, 248, 245),
          deleteBackground = Color.rgb(255, 230, 231),
          addBar = Color.rgb(0, 202, 177),
          deleteBar = Color.rgb(255, 46, 63),
          addText = Color.rgb(25, 159, 67),
          deleteText = Color.rgb(213, 44, 54),
        )
      }
  }
}

private data class ReviewDiffNativeStyle(
  val rowHeight: Float,
  val contentWidth: Float,
  val changeBarWidth: Float,
  val gutterWidth: Float,
  val codePadding: Float,
  val textVerticalInset: Float,
  val fileHeaderHeight: Float,
  val fileHeaderHorizontalPadding: Float,
  val fileHeaderCountGap: Float,
  val codeFontSize: Float,
  val codeFontWeight: String,
  val lineNumberFontSize: Float,
  val lineNumberFontWeight: String,
  val hunkFontSize: Float,
  val hunkFontWeight: String,
  val fileHeaderFontSize: Float,
  val fileHeaderFontWeight: String,
  val fileHeaderMetaFontSize: Float,
  val fileHeaderMetaFontWeight: String,
  val fileHeaderSubtextFontSize: Float,
  val fileHeaderSubtextFontWeight: String,
  val emptyStateFontSize: Float,
  val emptyStateFontWeight: String,
) {
  companion object {
    fun resolve(
      context: Context,
      payload: JSONObject?,
      rowHeightOverride: Double?,
      contentWidthOverride: Double?,
    ): ReviewDiffNativeStyle {
      val density = context.resources.displayMetrics.density
      val scaledDensity = context.resources.displayMetrics.scaledDensity
      fun dp(name: String, fallback: Double) = metric(payload, name, fallback) * density
      fun sp(name: String, fallback: Double) = metric(payload, name, fallback) * scaledDensity
      return ReviewDiffNativeStyle(
        rowHeight = ((rowHeightOverride ?: metric(payload, "rowHeight", 24.0)) * density).toFloat(),
        contentWidth = ((contentWidthOverride ?: metric(payload, "contentWidth", 2800.0)) * density).toFloat(),
        changeBarWidth = dp("changeBarWidth", 4.0).toFloat(),
        gutterWidth = dp("gutterWidth", 50.0).toFloat(),
        codePadding = dp("codePadding", 8.0).toFloat(),
        textVerticalInset = dp("textVerticalInset", 3.0).toFloat(),
        fileHeaderHeight = dp("fileHeaderHeight", 54.0).toFloat(),
        fileHeaderHorizontalPadding = dp("fileHeaderHorizontalPadding", 12.0).toFloat(),
        fileHeaderCountGap = dp("fileHeaderCountGap", 6.0).toFloat(),
        codeFontSize = sp("codeFontSize", 12.0).toFloat(),
        codeFontWeight = payload?.optStringOrNull("codeFontWeight") ?: "bold",
        lineNumberFontSize = sp("lineNumberFontSize", 11.0).toFloat(),
        lineNumberFontWeight = payload?.optStringOrNull("lineNumberFontWeight") ?: "bold",
        hunkFontSize = sp("hunkFontSize", 12.0).toFloat(),
        hunkFontWeight = payload?.optStringOrNull("hunkFontWeight") ?: "bold",
        fileHeaderFontSize = sp("fileHeaderFontSize", 13.0).toFloat(),
        fileHeaderFontWeight = payload?.optStringOrNull("fileHeaderFontWeight") ?: "bold",
        fileHeaderMetaFontSize = sp("fileHeaderMetaFontSize", 12.0).toFloat(),
        fileHeaderMetaFontWeight = payload?.optStringOrNull("fileHeaderMetaFontWeight") ?: "bold",
        fileHeaderSubtextFontSize = sp("fileHeaderSubtextFontSize", 11.0).toFloat(),
        fileHeaderSubtextFontWeight = payload?.optStringOrNull("fileHeaderSubtextFontWeight") ?: "medium",
        emptyStateFontSize = sp("emptyStateFontSize", 13.0).toFloat(),
        emptyStateFontWeight = payload?.optStringOrNull("emptyStateFontWeight") ?: "semibold",
      )
    }

    private fun metric(payload: JSONObject?, name: String, fallback: Double): Double {
      val value = payload?.optDouble(name, Double.NaN) ?: Double.NaN
      return if (value.isFinite() && value > 0) value else fallback
    }
  }
}

private data class VisibleRowRange(val first: Int, val last: Int)
private data class StickyFileHeaderTarget(val rowIndex: Int, val row: ReviewDiffNativeRow, val rect: RectF)
private data class FileHeaderInteractiveRects(val chevron: RectF, val checkbox: RectF)

private class ReviewDiffContentView(context: Context) : View(context) {
  var rows: List<ReviewDiffNativeRow> = emptyList()
    set(value) {
      field = value
      tokenTextWidthCache.clear()
      rebuildRowLayout()
    }
  var tokensByRowId: Map<String, List<ReviewDiffNativeToken>> = emptyMap()
    set(value) {
      field = value
      invalidate()
    }
  var collapsedFileIds: Set<String> = emptySet()
    set(value) {
      field = value
      rebuildRowLayout()
    }
  var viewedFileIds: Set<String> = emptySet()
    set(value) {
      field = value
      invalidate()
    }
  var selectedRowIds: Set<String> = emptySet()
    set(value) {
      field = value
      invalidate()
    }
  var collapsedCommentIds: Set<String> = emptySet()
    set(value) {
      field = value
      rebuildRowLayout()
    }
  var theme = ReviewDiffNativeTheme.resolve("light", null)
    set(value) {
      field = value
      invalidate()
    }
  var style = ReviewDiffNativeStyle.resolve(context, null, null, null)
    set(value) {
      field = value
      configurePaints()
      rebuildRowLayout()
    }
  var viewportWidth = 0f
  var viewportHeight = 0f
  var horizontalOffset = 0f
  var verticalOffset = 0f
  var contentHeight = 0f
    private set
  val contentPixelWidth: Float get() = max(style.contentWidth, viewportWidth)
  val rowCount: Int get() = rows.size
  val tokenRowCount: Int get() = tokensByRowId.size

  var onToggleFile: ((String) -> Unit)? = null
  var onToggleViewedFile: ((String) -> Unit)? = null
  var onPressLine: ((Map<String, Any>) -> Unit)? = null
  var onToggleComment: ((String) -> Unit)? = null
  var onDrawMetrics: ((Map<String, Any>) -> Unit)? = null
  var onContentMetricsChanged: (() -> Unit)? = null

  private val rowOffsets = mutableListOf<Float>()
  private val fileHeaderRowIndices = mutableListOf<Int>()
  private val tokenTextWidthCache = mutableMapOf<String, Float>()
  private val clipBounds = Rect()
  private val rowRect = RectF()
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val path = Path()
  private var lastDrawMetricsTimestamp = 0L

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(event: MotionEvent) = true

      override fun onSingleTapUp(event: MotionEvent): Boolean {
        performClick()
        handleTap(event.x, event.y)
        return true
      }

      override fun onLongPress(event: MotionEvent) {
        handleLongPress(event.x, event.y)
      }
    },
  )

  init {
    setWillNotDraw(false)
    configurePaints()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(ceil(contentPixelWidth).toInt(), max(1, ceil(contentHeight).toInt()))
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    parent.requestDisallowInterceptTouchEvent(false)
    gestureDetector.onTouchEvent(event)
    return true
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  fun mergeTokensByRowId(tokensPatch: Map<String, List<ReviewDiffNativeToken>>) {
    tokensByRowId = tokensByRowId + tokensPatch
  }

  fun frameForRow(index: Int): RectF? {
    if (index !in rows.indices || index !in rowOffsets.indices) return null
    val top = rowOffsets[index]
    return RectF(0f, top, contentPixelWidth, top + heightFor(rows[index]))
  }

  fun currentVisibleRowRange(): VisibleRowRange? {
    if (rows.isEmpty()) return null
    val first = firstVisibleRowIndex(verticalOffset) ?: return null
    val last = lastVisibleRowIndex(verticalOffset + max(viewportHeight, 1f)) ?: return null
    return if (first <= last) VisibleRowRange(first, last) else null
  }

  private fun configurePaints() {
    textPaint.typeface = typeface(style.codeFontWeight, monospace = true)
    textPaint.textSize = style.codeFontSize
  }

  private fun rebuildRowLayout() {
    rowOffsets.clear()
    fileHeaderRowIndices.clear()
    var offset = 0f
    rows.forEachIndexed { index, row ->
      rowOffsets.add(offset)
      if (row.kind == "file") fileHeaderRowIndices.add(index)
      offset += heightFor(row)
    }
    contentHeight = offset
    requestLayout()
    invalidate()
    onContentMetricsChanged?.invoke()
  }

  override fun onDraw(canvas: Canvas) {
    val startedAt = System.nanoTime()
    canvas.getClipBounds(clipBounds)
    paint.color = theme.background
    canvas.drawRect(clipBounds, paint)
    if (rows.isEmpty()) {
      drawEmptyState(canvas)
      return
    }
    val range = visibleRangeForDraw() ?: return
    var drawnRows = 0
    for (rowIndex in range.first..range.last) {
      val row = rows[rowIndex]
      val rowHeight = heightFor(row)
      if (rowHeight <= 0f) continue
      val top = rowOffsets[rowIndex]
      rowRect.set(0f, top, contentPixelWidth, top + rowHeight)
      if (!RectF.intersects(rowRect, RectF(clipBounds))) continue
      drawRow(canvas, row, rowIndex, rowRect)
      drawnRows += 1
    }
    drawStickyFileHeader(canvas)
    maybeEmitDrawMetrics(startedAt, drawnRows, range)
  }

  private fun visibleRangeForDraw(): VisibleRowRange? {
    val overscan = max(style.rowHeight, style.fileHeaderHeight) * 4f
    val first = firstVisibleRowIndex(max(0f, clipBounds.top - overscan)) ?: return null
    val last = lastVisibleRowIndex(clipBounds.bottom + overscan) ?: return null
    return if (first <= last) VisibleRowRange(first, last) else null
  }

  private fun maybeEmitDrawMetrics(startedAt: Long, drawnRows: Int, range: VisibleRowRange) {
    val now = System.nanoTime()
    if (now - lastDrawMetricsTimestamp < 1_000_000_000L) return
    lastDrawMetricsTimestamp = now
    onDrawMetrics?.invoke(
      mapOf(
        "drawnRows" to drawnRows,
        "durationMs" to ((now - startedAt).toDouble() / 1_000_000.0),
        "firstRowIndex" to range.first,
        "lastRowIndex" to range.last,
        "scannedRows" to (range.last - range.first + 1),
        "totalRows" to rows.size,
      ),
    )
  }

  private fun drawRow(canvas: Canvas, row: ReviewDiffNativeRow, rowIndex: Int, rect: RectF) {
    when (row.kind) {
      "file" -> drawFileRow(canvas, row, rect)
      "hunk" -> drawHunkRow(canvas, row, rect)
      "notice" -> drawNoticeRow(canvas, row, rect)
      "comment" -> drawCommentRow(canvas, row, rect)
      else -> drawCodeRow(canvas, row, rowIndex, rect)
    }
  }

  private fun drawEmptyState(canvas: Canvas) {
    configureText(theme.mutedText, style.emptyStateFontSize, style.emptyStateFontWeight)
    canvas.drawText("No native diff rows", horizontalOffset + dp(16f), verticalOffset + dp(32f), textPaint)
  }

  private fun drawFileRow(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    val headerRect = visibleHeaderRect(rect)
    paint.color = theme.headerBackground
    canvas.drawRect(headerRect, paint)
    paint.color = theme.border
    canvas.drawRect(headerRect.left, headerRect.bottom - hairline(), headerRect.right, headerRect.bottom, paint)
    val interactiveRects = fileHeaderInteractiveRects(headerRect)
    drawDisclosureChevron(canvas, interactiveRects.chevron, collapsedFileIds.contains(resolvedFileId(row)))
    drawFileIcon(canvas, interactiveRects.chevron.right + dp(8f), headerRect.centerY(), row.changeType)
    drawViewedCheckbox(canvas, interactiveRects.checkbox, viewedFileIds.contains(resolvedFileId(row)))
    drawFileCounts(canvas, row, headerRect, interactiveRects.checkbox.left)
    drawHeaderPath(canvas, row, headerRect)
  }

  private fun drawFileCounts(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF, right: Float) {
    configureText(theme.deleteText, style.fileHeaderMetaFontSize, style.fileHeaderMetaFontWeight)
    val deleteText = "-${row.deletions ?: 0}"
    val addText = "+${row.additions ?: 0}"
    val deleteWidth = textPaint.measureText(deleteText)
    val addWidth = textPaint.measureText(addText)
    val startX = right - dp(10f) - deleteWidth - style.fileHeaderCountGap - addWidth
    val baseline = textBaseline(rect, textPaint)
    canvas.drawText(deleteText, startX, baseline, textPaint)
    configureText(theme.addText, style.fileHeaderMetaFontSize, style.fileHeaderMetaFontWeight)
    canvas.drawText(addText, startX + deleteWidth + style.fileHeaderCountGap, baseline, textPaint)
  }

  private fun drawHeaderPath(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    val pathText = row.filePath ?: row.id
    val left = rect.left + style.fileHeaderHorizontalPadding + dp(42f)
    val right = rect.right - dp(116f)
    configureText(theme.text, style.fileHeaderFontSize, style.fileHeaderFontWeight)
    drawClippedText(canvas, pathText, RectF(left, rect.top, max(left, right), rect.bottom), textPaint)
  }

  private fun drawHunkRow(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    if (collapsedFileIds.contains(resolvedFileId(row))) return
    paint.color = theme.hunkBackground
    canvas.drawRect(rect, paint)
    configureText(theme.hunkText, style.hunkFontSize, style.hunkFontWeight, monospace = true)
    canvas.drawText(row.text ?: "", style.codePadding + horizontalOffset, textBaseline(rect, textPaint), textPaint)
  }

  private fun drawNoticeRow(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    if (collapsedFileIds.contains(resolvedFileId(row))) return
    paint.color = theme.background
    canvas.drawRect(rect, paint)
    paint.color = withAlpha(theme.border, 0.65f)
    canvas.drawRect(horizontalOffset, rect.bottom - hairline(), horizontalOffset + viewportWidth, rect.bottom, paint)
    configureText(theme.mutedText, style.fileHeaderSubtextFontSize, style.fileHeaderSubtextFontWeight)
    canvas.drawText(row.text ?: "", horizontalOffset + dp(18f), textBaseline(rect, textPaint), textPaint)
  }

  private fun drawCommentRow(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    if (collapsedFileIds.contains(resolvedFileId(row))) return
    val card = RectF(
      horizontalOffset + dp(8f),
      rect.top + dp(5f),
      horizontalOffset + viewportWidth - dp(8f),
      rect.bottom - dp(5f),
    )
    paint.color = theme.headerBackground
    canvas.drawRoundRect(card, dp(10f), dp(10f), paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = hairline()
    paint.color = theme.border
    canvas.drawRoundRect(card, dp(10f), dp(10f), paint)
    paint.style = Paint.Style.FILL
    val isCollapsed = collapsedCommentIds.contains(row.id)
    drawDisclosureChevron(
      canvas,
      RectF(card.left + dp(10f), card.top + dp(11f), card.left + dp(26f), card.top + dp(27f)),
      isCollapsed,
    )
    configureText(theme.mutedText, style.fileHeaderSubtextFontSize, style.fileHeaderSubtextFontWeight)
    canvas.drawText("Comment on ${row.commentRangeLabel ?: "line"}", card.left + dp(36f), card.top + dp(23f), textPaint)
    if (!isCollapsed) drawCommentBody(canvas, row, card)
  }

  private fun drawCommentBody(canvas: Canvas, row: ReviewDiffNativeRow, card: RectF) {
    configureText(theme.text, style.fileHeaderSubtextFontSize, style.fileHeaderSubtextFontWeight)
    val body = row.commentText?.trim().orEmpty().ifEmpty { "Comment" }
    val lines = wrapLines(body, card.width() - dp(36f), textPaint, 3)
    var baseline = card.top + dp(52f)
    for (line in lines) {
      canvas.drawText(line, card.left + dp(18f), baseline, textPaint)
      baseline += textPaint.fontSpacing
    }
  }

  private fun drawCodeRow(canvas: Canvas, row: ReviewDiffNativeRow, rowIndex: Int, rect: RectF) {
    val background = backgroundForChange(row.change)
    paint.color = background
    canvas.drawRect(rect, paint)
    if (selectedRowIds.contains(row.id)) {
      paint.color = withAlpha(theme.hunkText, 0.18f)
      canvas.drawRect(rect, paint)
    }
    drawWordDiffRanges(canvas, row, rect)
    drawCodeText(canvas, row, rect)
    drawStickyGutter(canvas, row, rect, background, rowIndex)
  }

  private fun drawCodeText(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    val baseline = rect.top + style.textVerticalInset - codeFontMetrics().ascent
    val tokens = tokensByRowId[row.id].orEmpty()
    if (tokens.isEmpty()) {
      configureText(theme.text, style.codeFontSize, style.codeFontWeight, monospace = true)
      canvas.drawText(row.content.orEmpty().ifEmpty { " " }, codeStartX(), baseline, textPaint)
      return
    }
    var x = codeStartX()
    for (token in tokens) {
      configureTokenPaint(token)
      canvas.drawText(token.content.ifEmpty { " " }, x, baseline, textPaint)
      x += tokenWidth(row.id, token)
      if (x > horizontalOffset + viewportWidth + dp(80f)) break
    }
  }

  private fun drawWordDiffRanges(canvas: Canvas, row: ReviewDiffNativeRow, rect: RectF) {
    val content = row.content ?: return
    val ranges = row.wordDiffRanges
    if (ranges.isEmpty()) return
    configureText(theme.text, style.codeFontSize, style.codeFontWeight, monospace = true)
    paint.color = withAlpha(if (row.change == "delete") theme.deleteBar else theme.addBar, 0.22f)
    for (range in ranges) {
      val start = range.start.coerceIn(0, content.length)
      val end = range.end.coerceIn(start, content.length)
      val left = codeStartX() + textPaint.measureText(content, 0, start)
      val right = codeStartX() + textPaint.measureText(content, 0, end)
      canvas.drawRoundRect(RectF(left, rect.top + dp(3f), right, rect.bottom - dp(3f)), dp(3f), dp(3f), paint)
    }
  }

  private fun drawStickyGutter(
    canvas: Canvas,
    row: ReviewDiffNativeRow,
    rect: RectF,
    background: Int,
    rowIndex: Int,
  ) {
    val left = horizontalOffset
    paint.color = background
    canvas.drawRect(left, rect.top, left + stickyWidth(), rect.bottom, paint)
    paint.color = barColorForChange(row.change)
    canvas.drawRect(left, rect.top, left + style.changeBarWidth, rect.bottom, paint)
    configureText(theme.mutedText, style.lineNumberFontSize, style.lineNumberFontWeight, monospace = true)
    val oldText = row.oldLineNumber?.toString().orEmpty()
    val newText = row.newLineNumber?.toString().orEmpty()
    val baseline = textBaseline(rect, textPaint)
    canvas.drawText(oldText, left + dp(8f), baseline, textPaint)
    canvas.drawText(newText, left + style.gutterWidth * 0.52f, baseline, textPaint)
    if (rowIndex > 0) drawRowBorder(canvas, rect)
  }

  private fun drawStickyFileHeader(canvas: Canvas) {
    val target = stickyFileHeaderTarget() ?: return
    drawFileRow(canvas, target.row, target.rect)
  }

  private fun handleTap(x: Float, y: Float) {
    stickyFileHeaderTarget()?.takeIf { it.rect.contains(x, y) }?.let {
      handleFileHeaderTap(it.row, it.rect, x, y)
      return
    }
    val rowIndex = rowIndexAt(y) ?: return
    val row = rows.getOrNull(rowIndex) ?: return
    when (row.kind) {
      "file" -> frameForRow(rowIndex)?.let { handleFileHeaderTap(row, it, x, y) }
      "comment" -> onToggleComment?.invoke(row.id)
      "line" -> onPressLine?.invoke(linePressPayload(row, "tap"))
    }
  }

  private fun handleLongPress(x: Float, y: Float) {
    if (stickyFileHeaderTarget()?.rect?.contains(x, y) == true) return
    val row = rowIndexAt(y)?.let(rows::getOrNull) ?: return
    if (row.kind == "line") onPressLine?.invoke(linePressPayload(row, "longPress"))
  }

  private fun handleFileHeaderTap(row: ReviewDiffNativeRow, rect: RectF, x: Float, y: Float) {
    val headerRect = visibleHeaderRect(rect)
    val interactiveRects = fileHeaderInteractiveRects(headerRect)
    val fileId = resolvedFileId(row)
    if (interactiveRects.checkbox.contains(x, y)) {
      onToggleViewedFile?.invoke(fileId)
    } else if (headerRect.contains(x, y)) {
      onToggleFile?.invoke(fileId)
    }
  }

  private fun linePressPayload(row: ReviewDiffNativeRow, gesture: String): Map<String, Any> =
    buildMap {
      put("rowId", row.id)
      put("fileId", resolvedFileId(row))
      put("gesture", gesture)
      row.oldLineNumber?.let { put("oldLineNumber", it) }
      row.newLineNumber?.let { put("newLineNumber", it) }
      row.change?.let { put("change", it) }
    }

  private fun heightFor(row: ReviewDiffNativeRow): Float {
    if (row.kind == "file") return style.fileHeaderHeight
    if (collapsedFileIds.contains(resolvedFileId(row))) return 0f
    if (row.kind == "notice") return max(style.rowHeight * 2f, dp(44f))
    if (row.kind == "comment") return if (collapsedCommentIds.contains(row.id)) dp(44f) else dp(124f)
    return style.rowHeight
  }

  private fun stickyFileHeaderTarget(): StickyFileHeaderTarget? {
    val stickyIndex = stickyFileHeaderRowIndex() ?: return null
    val nextIndex = fileHeaderRowIndices.getOrNull(fileHeaderRowIndices.indexOf(stickyIndex) + 1)
    val pushedY = nextIndex?.let { min(0f, rowOffsets[it] - verticalOffset - style.fileHeaderHeight) } ?: 0f
    if (pushedY <= -style.fileHeaderHeight) return null
    val rect = RectF(0f, verticalOffset + pushedY, contentPixelWidth, verticalOffset + pushedY + style.fileHeaderHeight)
    return StickyFileHeaderTarget(stickyIndex, rows[stickyIndex], rect)
  }

  private fun stickyFileHeaderRowIndex(): Int? {
    if (fileHeaderRowIndices.isEmpty()) return null
    var match: Int? = null
    for (rowIndex in fileHeaderRowIndices) {
      if (rowOffsets[rowIndex] <= verticalOffset) match = rowIndex else break
    }
    return match?.takeIf { rowOffsets[it] < verticalOffset }
  }

  private fun rowIndexAt(y: Float): Int? {
    var low = 0
    var high = rows.size - 1
    while (low <= high) {
      val mid = (low + high) / 2
      val start = rowOffsets[mid]
      val end = start + heightFor(rows[mid])
      if (y < start) high = mid - 1 else if (y >= end) low = mid + 1 else return mid
    }
    return null
  }

  private fun firstVisibleRowIndex(y: Float): Int? {
    var low = 0
    var high = rows.size
    while (low < high) {
      val mid = (low + high) / 2
      if (rowOffsets[mid] + heightFor(rows[mid]) < y) low = mid + 1 else high = mid
    }
    return low.takeIf { it < rows.size }
  }

  private fun lastVisibleRowIndex(y: Float): Int? {
    var low = 0
    var high = rows.size
    while (low < high) {
      val mid = (low + high) / 2
      if (rowOffsets[mid] <= y) low = mid + 1 else high = mid
    }
    return (low - 1).takeIf { it >= 0 }
  }

  private fun resolvedFileId(row: ReviewDiffNativeRow): String =
    row.fileId ?: row.filePath ?: row.id.substringBefore(":header").substringBefore(":hunk:").substringBefore(":line:")

  private fun visibleHeaderRect(rect: RectF): RectF =
    RectF(horizontalOffset, rect.top, horizontalOffset + max(viewportWidth, dp(320f)), rect.bottom)

  private fun fileHeaderInteractiveRects(rect: RectF): FileHeaderInteractiveRects =
    FileHeaderInteractiveRects(
      chevron = RectF(rect.left + dp(10f), rect.centerY() - dp(8f), rect.left + dp(26f), rect.centerY() + dp(8f)),
      checkbox = RectF(rect.right - dp(34f), rect.centerY() - dp(10f), rect.right - dp(14f), rect.centerY() + dp(10f)),
    )

  private fun drawDisclosureChevron(canvas: Canvas, rect: RectF, collapsed: Boolean) {
    paint.color = theme.mutedText
    path.reset()
    if (collapsed) {
      path.moveTo(rect.left + dp(5f), rect.top + dp(3f))
      path.lineTo(rect.right - dp(4f), rect.centerY())
      path.lineTo(rect.left + dp(5f), rect.bottom - dp(3f))
    } else {
      path.moveTo(rect.left + dp(3f), rect.top + dp(5f))
      path.lineTo(rect.centerX(), rect.bottom - dp(4f))
      path.lineTo(rect.right - dp(3f), rect.top + dp(5f))
    }
    path.close()
    canvas.drawPath(path, paint)
  }

  private fun drawFileIcon(canvas: Canvas, left: Float, centerY: Float, changeType: String?) {
    paint.color = when (changeType) {
      "new" -> theme.addBar
      "deleted" -> theme.deleteBar
      "renamed", "rename-pure", "rename-changed" -> theme.hunkText
      else -> theme.mutedText
    }
    canvas.drawRoundRect(RectF(left, centerY - dp(8f), left + dp(16f), centerY + dp(8f)), dp(3f), dp(3f), paint)
  }

  private fun drawViewedCheckbox(canvas: Canvas, rect: RectF, checked: Boolean) {
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = dp(1.5f)
    paint.color = if (checked) theme.addBar else theme.mutedText
    canvas.drawRoundRect(rect, dp(5f), dp(5f), paint)
    paint.style = Paint.Style.FILL
    if (!checked) return
    canvas.drawRect(rect.left + dp(5f), rect.centerY(), rect.centerX(), rect.bottom - dp(5f), paint)
    canvas.drawRect(rect.centerX(), rect.top + dp(5f), rect.right - dp(5f), rect.bottom - dp(5f), paint)
  }

  private fun drawRowBorder(canvas: Canvas, rect: RectF) {
    paint.color = withAlpha(theme.border, 0.45f)
    canvas.drawRect(horizontalOffset, rect.top, horizontalOffset + viewportWidth, rect.top + hairline(), paint)
  }

  private fun backgroundForChange(change: String?): Int =
    when (change) {
      "add" -> theme.addBackground
      "delete" -> theme.deleteBackground
      else -> theme.background
    }

  private fun barColorForChange(change: String?): Int =
    when (change) {
      "add" -> theme.addBar
      "delete" -> theme.deleteBar
      else -> withAlpha(theme.border, 0.7f)
    }

  private fun codeStartX(): Float = style.changeBarWidth + style.gutterWidth + style.codePadding
  private fun stickyWidth(): Float = style.changeBarWidth + style.gutterWidth
  private fun hairline(): Float = 1f / resources.displayMetrics.density
  private fun dp(value: Float): Float = value * resources.displayMetrics.density

  private fun configureText(
    color: Int,
    size: Float,
    weight: String,
    monospace: Boolean = false,
    italic: Boolean = false,
  ) {
    textPaint.color = color
    textPaint.textSize = size
    textPaint.typeface = typeface(weight, monospace, italic)
    textPaint.style = Paint.Style.FILL
  }

  private fun configureTokenPaint(token: ReviewDiffNativeToken) {
    val bold = token.fontStyle?.and(2) == 2
    val italic = token.fontStyle?.and(1) == 1
    configureText(
      token.color ?: theme.text,
      style.codeFontSize,
      if (bold) "bold" else style.codeFontWeight,
      monospace = true,
      italic = italic,
    )
  }

  private fun typeface(weight: String, monospace: Boolean = false, italic: Boolean = false): Typeface {
    val styleValue = if (weight.lowercase().contains("bold") || weight.lowercase() == "semibold") {
      if (italic) Typeface.BOLD_ITALIC else Typeface.BOLD
    } else if (italic) {
      Typeface.ITALIC
    } else {
      Typeface.NORMAL
    }
    return Typeface.create(if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT, styleValue)
  }

  private fun codeFontMetrics(): Paint.FontMetrics {
    configureText(theme.text, style.codeFontSize, style.codeFontWeight, monospace = true)
    return textPaint.fontMetrics
  }

  private fun textBaseline(rect: RectF, textPaint: Paint): Float {
    val metrics = textPaint.fontMetrics
    return rect.centerY() - (metrics.ascent + metrics.descent) / 2f
  }

  private fun drawClippedText(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
    canvas.save()
    canvas.clipRect(rect)
    canvas.drawText(text, rect.left, textBaseline(rect, paint), paint)
    canvas.restore()
  }

  private fun wrapLines(text: String, width: Float, paint: Paint, maxLines: Int): List<String> {
    val words = text.replace('\n', ' ').split(Regex("\\s+")).filter(String::isNotEmpty)
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
      val next = if (current.isEmpty()) word else "$current $word"
      if (paint.measureText(next) <= width || current.isEmpty()) {
        current = next
      } else {
        lines.add(current)
        current = word
      }
      if (lines.size == maxLines) return lines
    }
    if (current.isNotEmpty() && lines.size < maxLines) lines.add(current)
    return lines
  }

  private fun tokenWidth(rowId: String, token: ReviewDiffNativeToken): Float {
    val key = "$rowId:${token.content}:${token.fontStyle}:${token.color}"
    return tokenTextWidthCache.getOrPut(key) { textPaint.measureText(token.content) }
  }
}

private fun parseRows(rowsJson: String): List<ReviewDiffNativeRow> {
  val array = JSONArray(rowsJson)
  return List(array.length()) { index ->
    val row = array.getJSONObject(index)
    ReviewDiffNativeRow(
      kind = row.getString("kind"),
      id = row.getString("id"),
      fileId = row.optStringOrNull("fileId"),
      filePath = row.optStringOrNull("filePath"),
      previousPath = row.optStringOrNull("previousPath"),
      changeType = row.optStringOrNull("changeType"),
      additions = row.optNullableInt("additions"),
      deletions = row.optNullableInt("deletions"),
      text = row.optStringOrNull("text"),
      content = row.optStringOrNull("content"),
      change = row.optStringOrNull("change"),
      oldLineNumber = row.optNullableInt("oldLineNumber"),
      newLineNumber = row.optNullableInt("newLineNumber"),
      wordDiffRanges = parseWordDiffRanges(row.optJSONArray("wordDiffRanges")),
      commentText = row.optStringOrNull("commentText"),
      commentRangeLabel = row.optStringOrNull("commentRangeLabel"),
      commentSectionTitle = row.optStringOrNull("commentSectionTitle"),
    )
  }
}

private fun parseWordDiffRanges(array: JSONArray?): List<ReviewDiffNativeWordDiffRange> {
  if (array == null) return emptyList()
  return List(array.length()) { index ->
    val range = array.getJSONObject(index)
    ReviewDiffNativeWordDiffRange(range.optInt("start"), range.optInt("end"))
  }
}

private fun parseTokensByRowId(payload: JSONObject): Map<String, List<ReviewDiffNativeToken>> {
  val result = mutableMapOf<String, List<ReviewDiffNativeToken>>()
  val keys = payload.keys()
  while (keys.hasNext()) {
    val rowId = keys.next()
    val tokens = payload.optJSONArray(rowId) ?: continue
    result[rowId] = List(tokens.length()) { index ->
      val token = tokens.getJSONObject(index)
      ReviewDiffNativeToken(
        content = token.optString("content"),
        color = token.optStringOrNull("color")?.let { parseColor(it, Color.TRANSPARENT) },
        fontStyle = token.optNullableInt("fontStyle"),
      )
    }
  }
  return result
}

private fun parseColor(value: String?, fallback: Int): Int =
  try {
    if (value.isNullOrBlank()) fallback else Color.parseColor(value)
  } catch (_: IllegalArgumentException) {
    fallback
  }

private fun JSONObject.optStringOrNull(name: String): String? =
  if (has(name) && !isNull(name)) optString(name).takeIf(String::isNotEmpty) else null

private fun JSONObject.optNullableInt(name: String): Int? =
  if (has(name) && !isNull(name)) optInt(name) else null

private fun withAlpha(color: Int, alpha: Float): Int =
  Color.argb(
    (Color.alpha(color) * alpha.coerceIn(0f, 1f)).roundToInt(),
    Color.red(color),
    Color.green(color),
    Color.blue(color),
  )
