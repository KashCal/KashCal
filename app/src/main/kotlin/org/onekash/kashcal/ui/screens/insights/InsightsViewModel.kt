package org.onekash.kashcal.ui.screens.insights

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.onekash.kashcal.domain.insights.AnalysisPeriod
import org.onekash.kashcal.domain.insights.Insight
import org.onekash.kashcal.domain.insights.InsightEngine
import org.onekash.kashcal.domain.insights.InsightsRepository
import org.onekash.kashcal.domain.insights.PeriodStats
import org.onekash.kashcal.domain.insights.TemporalClass
import javax.inject.Inject

data class InsightsUiState(
    val period: AnalysisPeriod = AnalysisPeriod.THIS_WEEK,
    val stats: PeriodStats = PeriodStats.EMPTY,
    val insights: List<Insight> = emptyList(),
    val deltaText: String? = null,
    val temporalClass: TemporalClass = TemporalClass.IN_PROGRESS,
    val isLoading: Boolean = true
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insightsRepository: InsightsRepository,
    private val insightEngine: InsightEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(InsightsUiState())
    val uiState: StateFlow<InsightsUiState> = _uiState.asStateFlow()

    init {
        loadInsights(AnalysisPeriod.THIS_WEEK)
    }

    fun onPeriodChange(period: AnalysisPeriod) {
        if (period == _uiState.value.period) return
        _uiState.update { it.copy(period = period) }
        loadInsights(period)
    }

    fun recompute() {
        loadInsights(_uiState.value.period)
    }

    fun resetToThisWeek() {
        if (_uiState.value.period != AnalysisPeriod.THIS_WEEK) {
            _uiState.update { it.copy(period = AnalysisPeriod.THIS_WEEK) }
        }
        loadInsights(AnalysisPeriod.THIS_WEEK)
    }

    private fun loadInsights(period: AnalysisPeriod) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val now = System.currentTimeMillis()
            val (stats, occurrences) = insightsRepository.getStatsWithOccurrences(period, now)
            val insights = insightEngine.computeInsights(stats, occurrences, now, context)
            val delta = insightsRepository.getDelta(period, stats, now)
            val temporal = insightsRepository.classifyPeriod(period, now)

            _uiState.update {
                it.copy(
                    stats = stats,
                    insights = insights,
                    deltaText = delta,
                    temporalClass = temporal,
                    isLoading = false
                )
            }
        }
    }
}
