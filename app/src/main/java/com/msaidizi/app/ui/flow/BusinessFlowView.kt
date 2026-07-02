package com.msaidizi.app.ui.flow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.msaidizi.app.R
import com.msaidizi.app.core.model.Trend
import com.msaidizi.app.ui.theme.AppColors

/**
 * Business Flow visualization — like M-Pesa but for business.
 *
 * Shows worker how money flows through their business:
 * Revenue → Expenses → Profit → Savings → Growth
 *
 * Visual design:
 * - Flow diagram with animated arrows
 * - Color-coded (green for revenue, red for expenses, blue for profit)
 * - Swipeable tabs (Today, Week, Month, Year)
 * - Touch to drill down into details
 *
 * M-Pesa shows: Balance → Transactions → Mini-statement
 * Msaidizi shows: Revenue → Expenses → Profit → Savings → Growth
 *
 * This is the core UX that makes workers understand their business.
 */
class BusinessFlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ─── Flow summary cards ───
    private lateinit var revenueCard: FlowMetricCard
    private lateinit var expenseCard: FlowMetricCard
    private lateinit var profitCard: FlowMetricCard
    private lateinit var savingsCard: FlowMetricCard

    // ─── Flow diagram ───
    private lateinit var flowDiagramContainer: FlowDiagramView

    // ─── Charts ───
    private lateinit var dailyFlowChart: BarChart
    private lateinit var trendLineChart: LineChart

    // ─── Health & Credit ───
    private lateinit var healthScoreBar: HealthScoreView
    private lateinit var creditReadinessBar: HealthScoreView

    // ─── Top items ───
    private lateinit var topItemsContainer: LinearLayout

    // ─── Period comparison ───
    private lateinit var comparisonText: TextView

    init {
        inflate(context, R.layout.view_business_flow, this)
        setupViews()
    }

    private fun setupViews() {
        // Flow metric cards
        revenueCard = findViewById(R.id.card_revenue)
        expenseCard = findViewById(R.id.card_expenses)
        profitCard = findViewById(R.id.card_profit)
        savingsCard = findViewById(R.id.card_savings)

        // Flow diagram
        flowDiagramContainer = findViewById(R.id.flow_diagram)

        // Charts
        dailyFlowChart = findViewById(R.id.daily_flow_chart)
        trendLineChart = findViewById(R.id.trend_line_chart)

        // Health & credit
        healthScoreBar = findViewById(R.id.health_score_bar)
        creditReadinessBar = findViewById(R.id.credit_readiness_bar)

        // Top items
        topItemsContainer = findViewById(R.id.top_items_container)

        // Comparison
        comparisonText = findViewById(R.id.comparison_text)

        setupCharts()
    }

    private fun setupCharts() {
        // Daily flow bar chart
        dailyFlowChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            legend.isEnabled = true
            legend.textColor = AppColors.textPrimary

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = AppColors.textSecondary
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = AppColors.cardBorder
                axisMinimum = 0f
                textColor = AppColors.textSecondary
            }

            axisRight.isEnabled = false
            setFitBars(true)
        }

        // Trend line chart
        trendLineChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            legend.isEnabled = true
            legend.textColor = AppColors.textPrimary

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                textColor = AppColors.textSecondary
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = AppColors.cardBorder
                textColor = AppColors.textSecondary
            }

            axisRight.isEnabled = false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // RENDER METHODS — one per time period
    // ═══════════════════════════════════════════════════════════════

    /**
     * Render today's business flow.
     * Shows: Revenue today, Expenses today, Profit today, Top product.
     */
    fun renderTodayFlow(data: FlowData) {
        renderSummaryCards(data)
        renderFlowDiagram(data)
        renderTodayChart(data)
        renderTopItems(data.topItems)
        renderHealthScores(data)
        renderComparison(data)
    }

    /**
     * Render weekly business flow.
     * Shows: Daily revenue/expense/profit trend, Best/worst day, Cash position.
     */
    fun renderWeeklyFlow(data: FlowData) {
        renderSummaryCards(data)
        renderFlowDiagram(data)
        renderDailyBarChart(data)
        renderTrendLine(data)
        renderTopItems(data.topItems)
        renderHealthScores(data)
        renderComparison(data)
    }

    /**
     * Render monthly business flow.
     * Shows: Revenue by week, Expenses by category, Profit margin evolution.
     */
    fun renderMonthlyFlow(data: FlowData) {
        renderSummaryCards(data)
        renderFlowDiagram(data)
        renderDailyBarChart(data)
        renderTrendLine(data)
        renderCategoryBreakdown(data)
        renderTopItems(data.topItems)
        renderHealthScores(data)
        renderComparison(data)
    }

    /**
     * Render yearly business flow.
     * Shows: Monthly trends, Annual health, Goal tracking.
     */
    fun renderYearlyFlow(data: FlowData) {
        renderSummaryCards(data)
        renderFlowDiagram(data)
        renderDailyBarChart(data)
        renderTrendLine(data)
        renderCategoryBreakdown(data)
        renderTopItems(data.topItems)
        renderHealthScores(data)
        renderComparison(data)
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY CARDS — Revenue, Expenses, Profit, Savings
    // ═══════════════════════════════════════════════════════════════

    private fun renderSummaryCards(data: FlowData) {
        revenueCard.setValues(
            label = "Revenue",
            labelSw = "Mauzo",
            amount = data.revenue,
            color = AppColors.profitPositive,
            emoji = "💰",
            trend = data.previousPeriod?.revenueChange
        )

        expenseCard.setValues(
            label = "Expenses",
            labelSw = "Gharama",
            amount = data.expenses,
            color = AppColors.profitNegative,
            emoji = "📤",
            trend = data.previousPeriod?.expenseChange
        )

        profitCard.setValues(
            label = "Profit",
            labelSw = "Faida",
            amount = data.profit,
            color = if (data.profit >= 0) AppColors.profitPositive else AppColors.profitNegative,
            emoji = if (data.profit >= 0) "📈" else "📉",
            trend = data.previousPeriod?.profitChange
        )

        savingsCard.setValues(
            label = "Savings",
            labelSw = "Akiba",
            amount = data.savings,
            color = AppColors.info,
            emoji = "🏦",
            progress = if (data.savingsTarget > 0) (data.savings / data.savingsTarget * 100).toInt() else 0
        )

        // Animate cards
        animateCards()
    }

    private fun animateCards() {
        val cards = listOf(revenueCard, expenseCard, profitCard, savingsCard)
        cards.forEachIndexed { index, card ->
            card.alpha = 0f
            card.translationY = 50f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay(index * 80L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FLOW DIAGRAM — Animated flow visualization
    // ═══════════════════════════════════════════════════════════════

    private fun renderFlowDiagram(data: FlowData) {
        flowDiagramContainer.setData(data)
        flowDiagramContainer.animateFlow()
    }

    // ═══════════════════════════════════════════════════════════════
    // CHARTS — Daily breakdown and trends
    // ═══════════════════════════════════════════════════════════════

    private fun renderTodayChart(data: FlowData) {
        // For today, show a simple breakdown bar
        val entries = listOf(
            BarEntry(0f, data.revenue.toFloat()),
            BarEntry(1f, data.expenses.toFloat()),
            BarEntry(2f, data.profit.toFloat())
        )

        val dataSet = BarDataSet(entries, "").apply {
            colors = listOf(
                AppColors.profitPositive,
                AppColors.profitNegative,
                if (data.profit >= 0) AppColors.info else AppColors.profitNegative
            )
            setDrawValues(true)
            valueTextColor = AppColors.textPrimary
            valueTextSize = 11f
        }

        dailyFlowChart.data = BarData(dataSet)
        dailyFlowChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            listOf("Mauzo", "Gharama", "Faida")
        )
        dailyFlowChart.animateY(500)
        dailyFlowChart.invalidate()
    }

    private fun renderDailyBarChart(data: FlowData) {
        if (data.dailyBreakdown.isEmpty()) {
            dailyFlowChart.clear()
            return
        }

        val revenueEntries = data.dailyBreakdown.mapIndexed { index, day ->
            BarEntry(index.toFloat(), day.revenue.toFloat())
        }
        val expenseEntries = data.dailyBreakdown.mapIndexed { index, day ->
            BarEntry(index.toFloat(), day.expenses.toFloat())
        }

        val revenueSet = BarDataSet(revenueEntries, "Mauzo").apply {
            color = AppColors.profitPositive
            setDrawValues(false)
        }
        val expenseSet = BarDataSet(expenseEntries, "Gharama").apply {
            color = AppColors.profitNegative
            setDrawValues(false)
        }

        val barData = BarData(revenueSet, expenseSet).apply {
            val groupSpace = 0.1f
            val barSpace = 0.05f
            val barWidth = 0.4f
            groupBars(0f, groupSpace, barSpace)
        }

        dailyFlowChart.data = barData
        dailyFlowChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            data.dailyBreakdown.map { it.label }
        )
        dailyFlowChart.xAxis.labelCount = data.dailyBreakdown.size
        dailyFlowChart.animateY(600)
        dailyFlowChart.invalidate()
    }

    private fun renderTrendLine(data: FlowData) {
        if (data.dailyBreakdown.isEmpty()) {
            trendLineChart.clear()
            return
        }

        val profitEntries = data.dailyBreakdown.mapIndexed { index, day ->
            Entry(index.toFloat(), day.profit.toFloat())
        }

        val profitLine = LineDataSet(profitEntries, "Faida").apply {
            color = AppColors.info
            lineWidth = 2.5f
            setCircleColor(AppColors.info)
            circleRadius = 3f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }

        // Zero line for reference
        val zeroEntries = data.dailyBreakdown.mapIndexed { index, _ ->
            Entry(index.toFloat(), 0f)
        }
        val zeroLine = LineDataSet(zeroEntries, "").apply {
            color = AppColors.cardBorder
            lineWidth = 1f
            setDrawCircles(false)
            setDrawValues(false)
            enableDashedLine(10f, 5f, 0f)
        }

        trendLineChart.data = LineData(profitLine, zeroLine)
        trendLineChart.xAxis.valueFormatter = IndexAxisValueFormatter(
            data.dailyBreakdown.map { it.label }
        )
        trendLineChart.animateX(600)
        trendLineChart.invalidate()
    }

    // ═══════════════════════════════════════════════════════════════
    // CATEGORY BREAKDOWN
    // ═══════════════════════════════════════════════════════════════

    private fun renderCategoryBreakdown(data: FlowData) {
        // Category breakdown is shown in the flow diagram
        // Additional category cards could be added here
    }

    // ═══════════════════════════════════════════════════════════════
    // TOP ITEMS — Best selling products
    // ═══════════════════════════════════════════════════════════════

    private fun renderTopItems(items: List<com.msaidizi.app.core.model.ItemRanking>) {
        topItemsContainer.removeAllViews()

        if (items.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "Hakuna mauzo bado"
                setTextColor(AppColors.textSecondary)
                textSize = 14f
                setPadding(16, 16, 16, 16)
            }
            topItemsContainer.addView(emptyText)
            return
        }

        items.take(5).forEachIndexed { index, item ->
            val itemView = LayoutInflater.from(context)
                .inflate(R.layout.item_top_seller, topItemsContainer, false)

            itemView.findViewById<TextView>(R.id.item_rank).text = "#${index + 1}"
            itemView.findViewById<TextView>(R.id.item_name).text = item.item
            itemView.findViewById<TextView>(R.id.item_revenue).text =
                "KSh ${"%,.0f".format(item.totalRevenue)}"

            topItemsContainer.addView(itemView)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HEALTH SCORES — Business health and credit readiness
    // ═══════════════════════════════════════════════════════════════

    private fun renderHealthScores(data: FlowData) {
        healthScoreBar.setScore(
            score = data.healthScore,
            label = "Business Health",
            labelSw = "Afya ya Biashara",
            status = HealthStatus.fromScore(data.healthScore)
        )

        creditReadinessBar.setScore(
            score = data.creditReadiness,
            label = "Credit Readiness",
            labelSw = "Utayari wa Mkopo",
            status = when {
                data.creditReadiness >= 60 -> HealthStatus.GOOD
                data.creditReadiness >= 40 -> HealthStatus.FAIR
                else -> HealthStatus.POOR
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // COMPARISON — vs previous period
    // ═══════════════════════════════════════════════════════════════

    private fun renderComparison(data: FlowData) {
        val comparison = data.previousPeriod
        if (comparison == null) {
            comparisonText.visibility = View.GONE
            return
        }

        comparisonText.visibility = View.VISIBLE

        val revenueIcon = if (comparison.revenueChange >= 0) "📈" else "📉"
        val profitIcon = if (comparison.profitChange >= 0) "📈" else "📉"

        val periodLabel = when (data.period) {
            FlowPeriod.TODAY -> "jana"
            FlowPeriod.WEEK -> "wiki iliyopita"
            FlowPeriod.MONTH -> "mwezi uliopita"
            FlowPeriod.YEAR -> "mwaka uliopita"
        }

        comparisonText.text = buildString {
            append("$revenueIcon Mauzo: ")
            append(if (comparison.revenueChange >= 0) "+" else "")
            append("${"%.0f".format(comparison.revenueChange)}% vs $periodLabel")
            append("\n")
            append("$profitIcon Faida: ")
            append(if (comparison.profitChange >= 0) "+" else "")
            append("${"%.0f".format(comparison.profitChange)}% vs $periodLabel")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ANIMATED FLOW DIAGRAM — Custom view for flow visualization
    // ═══════════════════════════════════════════════════════════════

    /**
     * Custom view that draws the animated flow diagram.
     * Shows: Revenue → Business → Expenses → Profit → Savings
     */
    class FlowDiagramView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var flowData: FlowData? = null
        private var animationProgress = 0f

        // Node positions
        private val nodeRadius = 60f
        private val nodePositions = mutableListOf<NodePosition>()

        data class NodePosition(
            val x: Float,
            val y: Float,
            val label: String,
            val value: String,
            val color: Int
        )

        fun setData(data: FlowData) {
            flowData = data
            calculateNodePositions()
            invalidate()
        }

        fun animateFlow() {
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    animationProgress = animation.animatedValue as Float
                    invalidate()
                }
            }
            animator.start()
        }

        private fun calculateNodePositions() {
            val data = flowData ?: return
            nodePositions.clear()

            val width = width.toFloat().coerceAtLeast(300f)
            val height = height.toFloat().coerceAtLeast(200f)

            // Layout: horizontal flow
            val nodeCount = 5
            val spacing = width / (nodeCount + 1)
            val centerY = height / 2

            nodePositions.add(NodePosition(spacing, centerY, "Mauzo", "KSh ${"%,.0f".format(data.revenue)}", AppColors.profitPositive))
            nodePositions.add(NodePosition(spacing * 2, centerY, "Biashara", "${data.transactionCount} mauzo", AppColors.primary))
            nodePositions.add(NodePosition(spacing * 3, centerY, "Gharama", "KSh ${"%,.0f".format(data.expenses)}", AppColors.profitNegative))
            nodePositions.add(NodePosition(spacing * 4, centerY, "Faida", "KSh ${"%,.0f".format(data.profit)}", AppColors.info))
            nodePositions.add(NodePosition(spacing * 5, centerY, "Akiba", "KSh ${"%,.0f".format(data.savings)}", AppColors.accent))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            calculateNodePositions()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (nodePositions.isEmpty()) return

            // Draw arrows between nodes
            for (i in 0 until nodePositions.size - 1) {
                val from = nodePositions[i]
                val to = nodePositions[i + 1]
                drawArrow(canvas, from, to, animationProgress)
            }

            // Draw nodes
            nodePositions.forEachIndexed { index, node ->
                val nodeAlpha = ((animationProgress - index * 0.15f) * 3).coerceIn(0f, 1f)
                drawNode(canvas, node, nodeAlpha)
            }
        }

        private fun drawNode(canvas: Canvas, node: NodePosition, alpha: Float) {
            // Background circle
            paint.color = node.color
            paint.alpha = (alpha * 255).toInt()
            paint.style = Paint.Style.FILL
            canvas.drawCircle(node.x, node.y, nodeRadius * alpha, paint)

            // Border
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawCircle(node.x, node.y, nodeRadius * alpha, paint)

            // Label
            textPaint.color = Color.WHITE
            textPaint.textSize = 14f * alpha
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(node.label, node.x, node.y - 8f, textPaint)

            // Value
            textPaint.textSize = 11f * alpha
            canvas.drawText(node.value, node.x, node.y + 16f, textPaint)
        }

        private fun drawArrow(canvas: Canvas, from: NodePosition, to: NodePosition, progress: Float) {
            val startX = from.x + nodeRadius + 10f
            val endX = to.x - nodeRadius - 10f
            val y = from.y

            // Animated arrow line
            val animatedEndX = startX + (endX - startX) * progress.coerceIn(0f, 1f)

            arrowPaint.color = AppColors.cardBorder
            arrowPaint.strokeWidth = 4f
            arrowPaint.style = Paint.Style.STROKE
            canvas.drawLine(startX, y, animatedEndX, y, arrowPaint)

            // Arrow head
            if (progress > 0.8f) {
                val arrowSize = 12f
                arrowPaint.style = Paint.Style.FILL
                val path = Path().apply {
                    moveTo(animatedEndX, y)
                    lineTo(animatedEndX - arrowSize, y - arrowSize / 2)
                    lineTo(animatedEndX - arrowSize, y + arrowSize / 2)
                    close()
                }
                canvas.drawPath(path, arrowPaint)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FLOW METRIC CARD — Individual metric display
    // ═══════════════════════════════════════════════════════════════

    class FlowMetricCard @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr) {

        private val emojiText: TextView
        private val labelText: TextView
        private val amountText: TextView
        private val trendText: TextView
        private val progressBar: View?
        private val progressContainer: View?

        init {
            inflate(context, R.layout.card_flow_metric, this)
            emojiText = findViewById(R.id.metric_emoji)
            labelText = findViewById(R.id.metric_label)
            amountText = findViewById(R.id.metric_amount)
            trendText = findViewById(R.id.metric_trend)
            progressBar = findViewById(R.id.metric_progress)
            progressContainer = findViewById(R.id.metric_progress_container)
        }

        fun setValues(
            label: String,
            labelSw: String,
            amount: Double,
            color: Int,
            emoji: String,
            trend: Double? = null,
            progress: Int? = null
        ) {
            emojiText.text = emoji
            labelText.text = labelSw
            amountText.text = "KSh ${"%,.0f".format(amount)}"
            amountText.setTextColor(color)

            // Trend indicator
            if (trend != null) {
                trendText.visibility = View.VISIBLE
                val icon = if (trend >= 0) "↑" else "↓"
                trendText.text = "$icon ${"%.0f".format(kotlin.math.abs(trend))}%"
                trendText.setTextColor(
                    if (trend >= 0) AppColors.profitPositive else AppColors.profitNegative
                )
            } else {
                trendText.visibility = View.GONE
            }

            // Progress bar (for savings)
            if (progress != null && progressBar != null && progressContainer != null) {
                progressContainer.visibility = View.VISIBLE
                progressBar.post {
                    val params = progressBar.layoutParams
                    params.width = progressContainer.width * progress / 100
                    progressBar.layoutParams = params
                }
            } else {
                progressContainer?.visibility = View.GONE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HEALTH SCORE VIEW — Animated score bar
    // ═══════════════════════════════════════════════════════════════

    class HealthScoreView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : FrameLayout(context, attrs, defStyleAttr) {

        private val scoreText: TextView
        private val labelText: TextView
        private val statusText: TextView
        private val scoreBar: View

        init {
            inflate(context, R.layout.view_health_score, this)
            scoreText = findViewById(R.id.health_score_value)
            labelText = findViewById(R.id.health_label)
            statusText = findViewById(R.id.health_status)
            scoreBar = findViewById(R.id.health_bar_fill)
        }

        fun setScore(score: Int, label: String, labelSw: String, status: HealthStatus) {
            labelText.text = labelSw
            scoreText.text = "$score/100"
            statusText.text = "${status.emoji} ${status.labelSw}"

            // Animate score bar
            val animator = ValueAnimator.ofInt(0, score).apply {
                duration = 800
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Int
                    val params = scoreBar.layoutParams
                    params.width = (scoreBar.parent as View).width * value / 100
                    scoreBar.layoutParams = params
                }
            }
            animator.start()

            // Set bar color based on status
            scoreBar.setBackgroundColor(
                when (status) {
                    HealthStatus.EXCELLENT -> AppColors.profitPositive
                    HealthStatus.GOOD -> AppColors.primaryLight
                    HealthStatus.FAIR -> AppColors.warning
                    HealthStatus.POOR -> AppColors.profitNegative
                }
            )
        }
    }
}
