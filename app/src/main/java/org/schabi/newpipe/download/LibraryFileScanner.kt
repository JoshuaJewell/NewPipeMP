/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import org.schabi.newpipe.download.library.LibraryFileMatcher
import org.schabi.newpipe.download.library.LocalAudioFile

// Lists audio files already in the configured audio download folder, including the
// "Library" subfolder and the playlist subfolders written by older bulk downloads.
// SAF providers can be slow to enumerate, so the walk is bounded in depth and size.
object LibraryFileScanner {
    private const val TAG = "LibraryFileScanner"
    private const val MAX_DIRECTORY_DEPTH = 2
    private const val MAX_FILES = 20_000

    @JvmStatic
    fun scan(context: Context, audioFolder: String?): List<LocalAudioFile> {
        if (audioFolder.isNullOrBlank()) return emptyList()
        return try {
            val uri = Uri.parse(audioFolder)
            when {
                ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true) -> {
                    val path = uri.path ?: return emptyList()
                    scanFileTree(File(path))
                }

                ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true) -> {
                    val root = documentRoot(context, uri)
                    root?.let { scanDocumentTree(it) } ?: emptyList()
                }

                // Older preferences may still hold a bare filesystem path.
                audioFolder.startsWith("/") -> scanFileTree(File(audioFolder))

                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not scan the audio download folder", e)
            emptyList()
        }
    }

    private fun documentRoot(context: Context, uri: Uri): DocumentFile? {
        val tree = try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: Exception) {
            null
        }
        if (tree != null) return tree
        return try {
            DocumentFile.fromSingleUri(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the audio download folder", e)
            null
        }
    }

    private fun scanFileTree(root: File): List<LocalAudioFile> {
        if (!root.exists()) return emptyList()
        val result = ArrayList<LocalAudioFile>()

        fun addFile(file: File, depth: Int, inLibrary: Boolean) {
            if (result.size >= MAX_FILES || !file.isFile) return
            val name = file.name
            if (!LibraryFileMatcher.isAudioFile(name)) return
            val size = try {
                file.length()
            } catch (e: SecurityException) {
                -1L
            }
            if (size == 0L) return
            result.add(
                LocalAudioFile(
                    name = name,
                    uri = Uri.fromFile(file).toString(),
                    size = size,
                    depth = depth,
                    inLibraryFolder = inLibrary
                )
            )
        }

        fun walk(dir: File, depth: Int, inLibrary: Boolean) {
            if (result.size >= MAX_FILES) return
            val children = try {
                dir.listFiles()
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not list ${dir.path}", e)
                return
            } ?: return
            for (child in children) {
                if (result.size >= MAX_FILES) break
                val libraryHere = inLibrary || child.name.equals(
                    LibraryDownloadJob.LIBRARY_FOLDER,
                    ignoreCase = true
                )
                if (child.isDirectory) {
                    if (depth < MAX_DIRECTORY_DEPTH) walk(child, depth + 1, libraryHere)
                } else {
                    addFile(child, depth, inLibrary)
                }
            }
        }

        if (root.isDirectory) walk(root, 0, false) else addFile(root, 0, false)
        return result
    }

    private fun scanDocumentTree(root: DocumentFile): List<LocalAudioFile> {
        val result = ArrayList<LocalAudioFile>()

        fun addFile(file: DocumentFile, depth: Int, inLibrary: Boolean) {
            if (result.size >= MAX_FILES || !file.isFile) return
            val name = file.name ?: return
            if (!LibraryFileMatcher.isAudioFile(name)) return
            val size = try {
                file.length()
            } catch (e: Exception) {
                -1L
            }
            if (size == 0L) return
            result.add(
                LocalAudioFile(
                    name = name,
                    uri = file.uri.toString(),
                    size = size,
                    depth = depth,
                    inLibraryFolder = inLibrary
                )
            )
        }

        fun walk(dir: DocumentFile, depth: Int, inLibrary: Boolean) {
            if (result.size >= MAX_FILES) return
            val children = try {
                dir.listFiles()
            } catch (e: Exception) {
                Log.w(TAG, "Could not list a download folder", e)
                return
            }
            for (child in children) {
                if (result.size >= MAX_FILES) break
                val name = child.name ?: continue
                val libraryHere = inLibrary || name.equals(
                    LibraryDownloadJob.LIBRARY_FOLDER,
                    ignoreCase = true
                )
                if (child.isDirectory) {
                    if (depth < MAX_DIRECTORY_DEPTH) walk(child, depth + 1, libraryHere)
                } else {
                    addFile(child, depth, inLibrary)
                }
            }
        }

        if (root.isDirectory) walk(root, 0, false) else addFile(root, 0, false)
        return result
    }
}
