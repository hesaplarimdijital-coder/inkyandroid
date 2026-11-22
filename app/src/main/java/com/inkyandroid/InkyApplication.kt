package com.inkyandroid

import android.app.Application
import com.inkyandroid.session.SessionEnvironment
import com.inkyandroid.settings.AppSettings
import com.inkyandroid.storage.ProjectStorage

/**
 * What every window of the app shares: the settings, the storage of projects, and the session
 * environment with the one thread all ink work runs on (the ink library must never run on two
 * threads at once; `ink-engine.md`).
 */
class InkyApplication : Application() {

    val settings: AppSettings get() = AppSettings.of(this)

    val storage: ProjectStorage get() = ProjectStorage.from(this)

    /** What a window's project session is made of: the process's one environment. */
    val sessionEnvironment: SessionEnvironment get() = SessionEnvironment.of(this)
}
