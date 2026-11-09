package com.kaiser.rivet.local

import android.app.Application

/**
 * Process-wide owner of the native model slot. The provider factory is not a
 * ViewModel, so this is the single bridge between provider construction and the
 * application-owned files/native context.
 */
object LocalRuntime {
    @Volatile
    private var manager: LocalModelManager? = null

    @Volatile
    var downloads: ModelDownloadManager? = null
        private set

    fun initialize(app: Application): LocalModelManager = synchronized(this) {
        manager ?: LocalModelManager(app).also { created ->
            manager = created
            downloads = ModelDownloadManager(app, created)
        }
    }

    fun models(): LocalModelManager? = manager

    fun requireModels(): LocalModelManager =
        manager ?: throw LocalFailure("Local models aren't available in this build.")
}
