package org.fossify.editor.activities

import org.fossify.commons.activities.BaseSimpleActivity

/**
 * Minimal commons base activity for the editor. Override in a subclass if you
 * want custom launcher icons / app name / repository for the commons dialogs.
 */
open class EditorBaseActivity : BaseSimpleActivity() {
    override fun getAppIconIDs() = arrayListOf(applicationInfo.icon)

    override fun getAppLauncherName() = applicationInfo.loadLabel(packageManager).toString()

    override fun getRepositoryName() = "basic-editor"
}
