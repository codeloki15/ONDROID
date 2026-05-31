package com.locallink.pro.di

import com.locallink.pro.service.llm.tools.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Registers every ToolHandler into the Set<ToolHandler> consumed by ToolRegistry. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolsModule {
    @Binds @IntoSet abstract fun datetime(t: DateTimeTool): ToolHandler
    @Binds @IntoSet abstract fun calculate(t: CalculateTool): ToolHandler
    @Binds @IntoSet abstract fun battery(t: BatteryTool): ToolHandler
    @Binds @IntoSet abstract fun flashlight(t: FlashlightTool): ToolHandler
    @Binds @IntoSet abstract fun setTimer(t: SetTimerTool): ToolHandler
    @Binds @IntoSet abstract fun setAlarm(t: SetAlarmTool): ToolHandler
    @Binds @IntoSet abstract fun setVolume(t: SetVolumeTool): ToolHandler
    @Binds @IntoSet abstract fun clipboard(t: ClipboardTool): ToolHandler
    @Binds @IntoSet abstract fun createNote(t: CreateNoteTool): ToolHandler
    @Binds @IntoSet abstract fun openSettings(t: OpenSettingsTool): ToolHandler
    @Binds @IntoSet abstract fun calendar(t: CreateCalendarEventTool): ToolHandler
    @Binds @IntoSet abstract fun contact(t: CreateContactTool): ToolHandler
    @Binds @IntoSet abstract fun sms(t: SendSmsTool): ToolHandler
    @Binds @IntoSet abstract fun dial(t: DialTool): ToolHandler

    // Expansion tools
    @Binds @IntoSet abstract fun makeCall(t: MakePhoneCallTool): ToolHandler
    @Binds @IntoSet abstract fun readContacts(t: ReadContactsTool): ToolHandler
    @Binds @IntoSet abstract fun sendEmail(t: SendEmailTool): ToolHandler
    @Binds @IntoSet abstract fun readCalendar(t: ReadCalendarTool): ToolHandler
    @Binds @IntoSet abstract fun scheduleReminder(t: ScheduleReminderTool): ToolHandler
    @Binds @IntoSet abstract fun sendNotification(t: SendNotificationTool): ToolHandler
    @Binds @IntoSet abstract fun launchApp(t: LaunchAppTool): ToolHandler
    @Binds @IntoSet abstract fun openUrl(t: OpenUrlTool): ToolHandler
    @Binds @IntoSet abstract fun webSearch(t: WebSearchTool): ToolHandler
}
