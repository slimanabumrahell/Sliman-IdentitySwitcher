package com.sliman.identity.ui

/** File access provided by the activity (Storage Access Framework), so screens stay free of Android plumbing. */
class FileIO(
    /** Ask the user where to save [text] as [fileName]. */
    val export: (fileName: String, text: String) -> Unit,
    /** Let the user pick a file; [onText] receives its content. */
    val import: (onText: (String) -> Unit) -> Unit,
)
