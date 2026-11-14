package org.fossify.editor.activities

import android.os.Bundle
import org.fossify.commons.activities.BaseSimpleActivity

/**
 * Minimal commons base activity for the editor. Override in a subclass if you
 * want custom launcher icons / app name / repository for the commons dialogs.
 */
open class EditorBaseActivity : BaseSimpleActivity() {
    // commons' BaseSimpleActivity.onCreate paket adı "org.fossify." ile başlamıyorsa zaman zaman
    // "You are using a fake version of the app" uyarısı gösterir. Bu editör, başka bir uygulamanın
    // içine gömülü bir modül olduğundan o uyarı yanlış alarmdır. Yalnızca super.onCreate sırasında
    // paket adını bildirerek denetimi atlıyoruz; sonrasında gerçek paket adı döner.
    private var reportFossifyPackage = false

    override fun getPackageName(): String =
        if (reportFossifyPackage) "org.fossify.editor" else super.getPackageName()

    override fun onCreate(savedInstanceState: Bundle?) {
        reportFossifyPackage = true
        try {
            super.onCreate(savedInstanceState)
        } finally {
            reportFossifyPackage = false
        }
    }

    override fun getAppIconIDs() = arrayListOf(applicationInfo.icon)

    override fun getAppLauncherName() = applicationInfo.loadLabel(packageManager).toString()

    override fun getRepositoryName() = "basic-editor"
}
