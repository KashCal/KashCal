package org.onekash.kashcal.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.onekash.kashcal.domain.insights.InsightGenerator
import org.onekash.kashcal.domain.insights.generators.BackToBackGenerator
import org.onekash.kashcal.domain.insights.generators.BusiestDayGenerator
import org.onekash.kashcal.domain.insights.generators.CalendarDominantGenerator
import org.onekash.kashcal.domain.insights.generators.EarlyLateBoundsGenerator
import org.onekash.kashcal.domain.insights.generators.HeaviestUpcomingGenerator
import org.onekash.kashcal.domain.insights.generators.LightestDayGenerator
import org.onekash.kashcal.domain.insights.generators.LongestFreeGenerator
import org.onekash.kashcal.domain.insights.generators.MeetingFreeDaysGenerator
import org.onekash.kashcal.domain.insights.generators.NextFreeBlockGenerator
import org.onekash.kashcal.domain.insights.generators.TomorrowPreviewGenerator
import org.onekash.kashcal.domain.insights.generators.WeekendLoadGenerator

@Module
@InstallIn(SingletonComponent::class)
abstract class InsightsModule {

    @Binds @IntoSet abstract fun bindBusiestDay(impl: BusiestDayGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindLightestDay(impl: LightestDayGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindLongestFree(impl: LongestFreeGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindWeekendLoad(impl: WeekendLoadGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindBackToBack(impl: BackToBackGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindCalendarDominant(impl: CalendarDominantGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindEarlyLateBounds(impl: EarlyLateBoundsGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindMeetingFreeDays(impl: MeetingFreeDaysGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindTomorrowPreview(impl: TomorrowPreviewGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindHeaviestUpcoming(impl: HeaviestUpcomingGenerator): InsightGenerator
    @Binds @IntoSet abstract fun bindNextFreeBlock(impl: NextFreeBlockGenerator): InsightGenerator
}
