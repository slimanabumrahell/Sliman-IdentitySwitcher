package com.sliman.identity.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.sliman.identity.model.DeviceProfile
import java.io.File

class ProfileProvider : ContentProvider() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        prefs = ctx.getSharedPreferences("sliman_identity_profiles", Context.MODE_PRIVATE)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val segments = uri.pathSegments
        val targetPkg = if (segments.size >= 2 && segments[0] == "profile") {
            segments[1]
        } else {
            return null
        }

        val activeProfileId = prefs.getString("active_profile_$targetPkg", null) ?: return null
        val jsonStr = prefs.getString("profile_data_$activeProfileId", null) ?: return null

        // التحقق من صلاحية JSON عبر DeviceProfile
        DeviceProfile.fromJson(jsonStr) ?: return null

        val cursor = MatrixCursor(arrayOf("json_data"))
        cursor.addRow(arrayOf(jsonStr))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.sliman.identity.profile"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        fun saveProfile(context: Context, profile: DeviceProfile, setActive: Boolean = true) {
            val sp = context.getSharedPreferences("sliman_identity_profiles", Context.MODE_PRIVATE)
            sp.edit().apply {
                putString("profile_data_${profile.id}", profile.toJson())
                if (setActive && profile.targetPackage.isNotBlank()) {
                    putString("active_profile_${profile.targetPackage}", profile.id)
                }
                apply()
            }

            if (setActive && profile.targetPackage.isNotBlank()) {
                try {
                    val dir = File("/data/local/tmp/sliman_identity")
                    if (!dir.exists()) dir.mkdirs()
                    val outFile = File(dir, "${profile.targetPackage}.json")
                    outFile.writeText(profile.toJson())
                    outFile.setReadable(true, false)
                    dir.setExecutable(true, false)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
