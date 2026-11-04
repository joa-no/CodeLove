/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Modified for use with LOVE by the LOVE Development Team
 * Copyright (C) 2024 LOVE Development Team
 */

package org.love2d.android;

import static android.os.Build.VERSION.SDK_INT;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Manages documents and exposes them to the Android system for sharing.
 */
public class LoveDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "LoveDocumentsProvider";

    // Use these as the default columns to return information about a root if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    // Use these as the default columns to return information about a document if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    // No official policy on how many to return, but make sure you do limit the number of recent
    // and search results.
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final int MAX_LAST_MODIFIED = 5;

    private static final String ROOT = "root";

    // This file is the base of our root
    private File mBaseDir;

    @Override
    public boolean onCreate() {
        Log.v(TAG, "onCreate");

        mBaseDir = Objects.requireNonNull(getContext()).getExternalFilesDir(null);
        if (mBaseDir == null) {
            Log.e(TAG, "External files directory is unavailable");
            return false;
        }
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Log.v(TAG, "queryRoots");

        // Create a cursor with either the requested fields, or the default projection.  This
        // cursor is returned to the Android system picker UI and used to display all roots from
        // this provider.
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));

        // It's possible to have multiple roots
        final MatrixCursor.RowBuilder row = result.newRow();

        row.add(Root.COLUMN_ROOT_ID, ROOT);
        row.add(Root.COLUMN_SUMMARY, null);

        // FLAG_SUPPORTS_CREATE means at least one directory under the root supports creating
        // documents.  FLAG_SUPPORTS_RECENTS means your application's most recently used
        // documents will show up in the "Recents" category.  FLAG_SUPPORTS_SEARCH allows users
        // to search all documents the application shares. FLAG_SUPPORTS_IS_CHILD allows
        // testing parent child relationships, available after SDK 21 (Lollipop).
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE |
            Root.FLAG_SUPPORTS_RECENTS |
            Root.FLAG_SUPPORTS_SEARCH |
            Root.FLAG_SUPPORTS_IS_CHILD);

        // COLUMN_TITLE is the root title (e.g. what will be displayed to identify your provider).
        row.add(Root.COLUMN_TITLE, Objects.requireNonNull(getContext()).getApplicationInfo().loadLabel(getContext().getPackageManager()).toString());

        // This document id must be unique within this provider and consistent across time.  The
        // system picker UI may save it and refer to it later.
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(mBaseDir));

        // The child MIME types are used to filter the roots and only present to the user roots
        // that contain the desired type somewhere in their file hierarchy.
        row.add(Root.COLUMN_MIME_TYPES, "*/*");
        row.add(Root.COLUMN_AVAILABLE_BYTES, mBaseDir.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.drawable.love);

        return result;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
        throws FileNotFoundException {
        Log.v(TAG, "queryRecentDocuments");

        // This implementation walks a local file structure to find the most recently
        // modified files.

        // Create a cursor with the requested projection, or the default projection.
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));

        final File parent = getFileForDocId(rootId);

        // Create a queue to store the most recent documents, which orders by last modified.
        PriorityQueue<File> lastModifiedFiles = new PriorityQueue<File>(5, new Comparator<File>() {
            public int compare(File i, File j) {
                return Long.compare(i.lastModified(), j.lastModified());
            }
        });

        // Iterate through all files and directories in the file structure under the root.  If
        // the file is more recent than the least recently modified, add it to the queue,
        // limiting the number of results.
        final LinkedList<File> pending = new LinkedList<File>();

        // Start by adding the parent to the list of files to be processed
        pending.add(parent);

        // Do while we still have unexamined files
        while (!pending.isEmpty()) {
            // Take a file from the list of unprocessed files
            final File file = pending.removeFirst();
            try {
                getCanonicalFileInsideRoot(file);
            } catch (FileNotFoundException e) {
                continue;
            }
            if (file.isDirectory()) {
                // If it's a directory, add all its children to the unprocessed list
                File[] files = file.listFiles();

                if (files != null) {
                    Collections.addAll(pending, files);
                }
            } else {
                // If it's a file, add it to the ordered queue.
                lastModifiedFiles.add(file);
            }
        }

        // Add the most recent files to the cursor, not exceeding the max number of results.
        int includedCount = 0;
        while (includedCount < MAX_LAST_MODIFIED + 1 && !lastModifiedFiles.isEmpty()) {
            final File file = lastModifiedFiles.remove();
            includeFile(result, null, file);
            includedCount++;
        }
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
        throws FileNotFoundException {
        Log.v(TAG, "querySearchDocuments");

        // Create a cursor with the requested projection, or the default projection.
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        final File parent = getFileForDocId(rootId);

        // This implementation searches file names for the query and doesn't rank search
        // results, so we can stop as soon as we find a sufficient number of matches.  Other
        // implementations might use other data about files, rather than the file name, to
        // produce a match;

        // Iterate through all files in the file structure under the root until we reach the
        // desired number of matches.
        final LinkedList<File> pending = new LinkedList<File>();

        // Start by adding the parent to the list of files to be processed
        pending.add(parent);

        // Do while we still have unexamined files, and fewer than the max search results
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
            // Take a file from the list of unprocessed files
            final File file = pending.removeFirst();
            try {
                getCanonicalFileInsideRoot(file);
            } catch (FileNotFoundException e) {
                continue;
            }
            if (file.isDirectory()) {
                // If it's a directory, add all its children to the unprocessed list
                File[] files = file.listFiles();

                if (files != null) {
                    Collections.addAll(pending, files);
                }
            } else {
                // If it's a file and it matches, add it to the result cursor.
                if (file.getName().toLowerCase().contains(query)) {
                    includeFile(result, null, file);
                }
            }
        }
        return result;
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint,
                                                     CancellationSignal signal)
        throws FileNotFoundException {
        Log.v(TAG, "openDocumentThumbnail");

        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
        throws FileNotFoundException {
        Log.v(TAG, "queryDocument");

        // Create a cursor with the requested projection, or the default projection.
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        Log.v(TAG, "queryChildDocuments, parentDocumentId: " +
            parentDocumentId +
            " sortOrder: " +
            sortOrder);

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        final File parent = getFileForDocId(parentDocumentId);
        File[] files = parent.listFiles();

        if (files != null) {
            for (File file : files) {
                try {
                    getCanonicalFileInsideRoot(file);
                    includeFile(result, null, file);
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "Skipping document outside provider root", e);
                }
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, final String mode, CancellationSignal signal) throws FileNotFoundException {
        Log.v(TAG, "openDocument, mode: " + mode);

        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    /** Returns true only when childFile is an immediate child of parentFile. */
    public boolean isChildFile(File parentFile, File childFile) {
        try {
            File canonicalParent = parentFile.getCanonicalFile();
            File canonicalChild = childFile.getCanonicalFile();
            return canonicalParent.equals(canonicalChild.getParentFile());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        Log.v(TAG, "isChildDocument");
        try {
            File parent = getFileForDocId(parentDocumentId);
            File child = getFileForDocId(documentId);
            String parentPath = parent.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.startsWith(parentPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String createDocument(String documentId, String mimeType, String displayName)
        throws FileNotFoundException {
        Log.v(TAG, "createDocument");

        File parent = getFileForDocId(documentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Document parent is not a directory: " + documentId);
        }

        validateDisplayName(displayName);
        int conflictId = 0;
        while (true) {
            String candidateName = conflictId == 0
                ? displayName
                : displayName + "(" + conflictId + ")";
            File file = getChildFile(parent, candidateName);
            try {
                boolean created = Document.MIME_TYPE_DIR.equals(mimeType)
                    ? file.mkdir()
                    : file.createNewFile();
                if (created) {
                    return getDocIdForFile(file);
                }
            } catch (IOException e) {
                FileNotFoundException error = new FileNotFoundException(
                    "Failed to create document with name " + displayName);
                error.initCause(e);
                throw error;
            }

            if (!file.exists()) {
                throw new FileNotFoundException(
                    "Failed to create document with name " + displayName);
            }
            conflictId++;
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName)
        throws FileNotFoundException {
        Log.v(TAG, "renameDocument");
        validateDisplayName(displayName);

        File sourceFile = getFileForDocId(documentId);
        if (sourceFile.equals(getCanonicalBaseDir())) {
            throw new FileNotFoundException("The provider root cannot be renamed");
        }

        File sourceParent = sourceFile.getParentFile();
        if (sourceParent == null || !sourceParent.isDirectory()) {
            throw new FileNotFoundException("The source document has no valid parent");
        }

        File destFile = getChildFile(sourceParent, displayName);
        if (sourceFile.equals(destFile)) {
            return getDocIdForFile(sourceFile);
        }
        if (destFile.exists()) {
            throw new FileNotFoundException("A document with that name already exists");
        }
        if (!sourceFile.renameTo(destFile)) {
            throw new FileNotFoundException("Failed to rename document " + documentId);
        }
        return getDocIdForFile(destFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Log.v(TAG, "deleteDocument");
        File file = getFileForDocId(documentId);
        if (file.equals(getCanonicalBaseDir())) {
            throw new FileNotFoundException("The provider root cannot be deleted");
        }
        if (file.delete()) {
            Log.i(TAG, "Deleted file with id " + documentId);
        } else {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId)
        throws FileNotFoundException {
        Log.v(TAG, "removeDocument");
        File parent = getFileForDocId(parentDocumentId);
        File file = getFileForDocId(documentId);

        if (file.equals(getCanonicalBaseDir()) || !isChildFile(parent, file)) {
            throw new FileNotFoundException(
                "Document is not a direct child of the supplied parent: " + documentId);
        }
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete document with id " + documentId);
        }
        Log.i(TAG, "Deleted file with id " + documentId);
    }

    /**
     * overload copyDocument to insist that the parent matches
     */
    public String copyDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        Log.v(TAG, "copyDocument with document parent");
        try {
            if (!isChildFile(getFileForDocId(sourceParentDocumentId),
                    getFileForDocId(sourceDocumentId))) {
                throw new FileNotFoundException("Failed to copy document with id " +
                    sourceDocumentId + ". Parent is not: " + sourceParentDocumentId);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to validate source parent");
        }
        return copyDocument(sourceDocumentId, targetParentDocumentId);
    }

    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId)
        throws FileNotFoundException {
        Log.v(TAG, "copyDocument");

        File parent = getFileForDocId(targetParentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Target parent is not a directory");
        }
        File oldFile = getFileForDocId(sourceDocumentId);
        if (oldFile.equals(getCanonicalBaseDir())) {
            throw new FileNotFoundException("The provider root cannot be copied");
        }

        int conflictId = 0;
        File newFile;
        boolean isDirectory = Document.MIME_TYPE_DIR.equals(getTypeForFile(oldFile));
        while (true) {
            String candidateName = conflictId == 0
                ? oldFile.getName()
                : oldFile.getName() + "(" + conflictId + ")";
            newFile = getChildFile(parent, candidateName);
            try {
                boolean created = isDirectory
                    ? newFile.mkdir()
                    : newFile.createNewFile();
                if (created) {
                    break;
                }
            } catch (IOException e) {
                FileNotFoundException error = new FileNotFoundException(
                    "Failed to create copy of " + sourceDocumentId);
                error.initCause(e);
                throw error;
            }
            if (!newFile.exists()) {
                throw new FileNotFoundException(
                    "Failed to create copy of " + sourceDocumentId);
            }
            conflictId++;
        }

        try {
            if (!isDirectory) {
                try (FileInputStream inStream = new FileInputStream(oldFile);
                     FileOutputStream outStream = new FileOutputStream(newFile)) {
                    byte[] buffer = new byte[16384];
                    int length;
                    while ((length = inStream.read(buffer)) != -1) {
                        outStream.write(buffer, 0, length);
                    }
                    outStream.flush();
                    outStream.getFD().sync();
                }
            }
            return getDocIdForFile(newFile);
        } catch (IOException e) {
            // Do not leave an incomplete copy behind.
            if (newFile.isDirectory()) {
                newFile.delete();
            } else {
                newFile.delete();
            }
            FileNotFoundException error = new FileNotFoundException(
                "Failed to copy document " + sourceDocumentId + ": " + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        Log.v(TAG, "moveDocument");
        try {
            // Copy document, insisting that the parent is correct
            String newDocumentId = copyDocument(sourceDocumentId, sourceParentDocumentId,
                targetParentDocumentId);
            // Remove old document
            removeDocument(sourceDocumentId, sourceParentDocumentId);
            return newDocumentId;
        } catch (FileNotFoundException e) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getTypeForFile(file);
    }

    /**
     * @param projection the requested root column projection
     * @return either the requested root column projection, or the default projection if the
     * requested projection is null.
     */
    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    /**
     * Get a file's MIME type
     *
     * @param file the File object whose type we want
     * @return the MIME type of the file
     */
    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }

    /**
     * Get the MIME data type of a document, given its filename.
     *
     * @param name the filename of the document
     * @return the MIME data type of a document
     */
    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            if (extension.equals("love")) {
                return "application/x-love-game";
            }
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    /**
     * Get the document ID given a File.  The document id must be consistent across time.  Other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * This implementation is specific to this demo.  It assumes only one root and is built
     * directly from the file structure.  However, it is possible for a document to be a child of
     * multiple directories (for example "android" and "images"), in which case the file must have
     * the same consistent, unique document ID in both cases.
     *
     * @param file the File whose document ID you want
     * @return the corresponding document ID
     */
    private String getDocIdForFile(File file) throws FileNotFoundException {
        File canonicalRoot = getCanonicalBaseDir();
        File canonicalFile = getCanonicalFileInsideRoot(file);
        String rootPath = canonicalRoot.getPath();
        String filePath = canonicalFile.getPath();

        if (rootPath.equals(filePath)) {
            return ROOT + ':';
        }

        String relativePath = filePath.substring(rootPath.length() + 1);
        return ROOT + ':' + relativePath;
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     */
    private void includeFile(MatrixCursor result, String docId, File file)
        throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;

        if (file.isDirectory()) {
            // Add FLAG_DIR_SUPPORTS_CREATE if the file is a writable directory.
            if (file.isDirectory() && file.canWrite()) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        } else if (file.canWrite()) {
            // If the file is writable set FLAG_SUPPORTS_WRITE and
            // FLAG_SUPPORTS_DELETE
            flags |= Document.FLAG_SUPPORTS_WRITE;
            flags |= Document.FLAG_SUPPORTS_DELETE;
            flags |= Document.FLAG_SUPPORTS_RENAME;

            if (SDK_INT >= Build.VERSION_CODES.N) {
                flags |= Document.FLAG_SUPPORTS_REMOVE;
                flags |= Document.FLAG_SUPPORTS_MOVE;
                flags |= Document.FLAG_SUPPORTS_COPY;
            }
        }

        final String displayName = file.getName();
        final String mimeType = getTypeForFile(file);

        if (mimeType.startsWith("image/")) {
            // Allow the image to be represented by a thumbnail rather than an icon
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        if (file.getAbsolutePath().equals(mBaseDir.getAbsolutePath())) {
            row.add(Document.COLUMN_DISPLAY_NAME, Objects.requireNonNull(getContext()).getApplicationInfo().loadLabel(getContext().getPackageManager()).toString());
        } else {
            row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        }
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);

        // Add a custom icon
        if (mimeType.equals("application/x-love-game")) {
            row.add(Document.COLUMN_ICON, R.drawable.love);
        } else {
            row.add(Document.COLUMN_ICON, null);
        }
    }

    /**
     * Translate your custom URI scheme into a File object.
     *
     * @param docId the document ID representing the desired file
     * @return a File represented by the given document ID
     */
    private File getFileForDocId(String docId) throws FileNotFoundException {
        if (docId == null) {
            throw new FileNotFoundException("Document ID is null");
        }

        File canonicalRoot = getCanonicalBaseDir();
        if (ROOT.equals(docId) || (ROOT + ":").equals(docId)) {
            return canonicalRoot;
        }
        if (!docId.startsWith(ROOT + ":")) {
            throw new FileNotFoundException("Invalid document root in ID: " + docId);
        }

        String relativePath = docId.substring(ROOT.length() + 1);
        if (relativePath.isEmpty() || relativePath.startsWith("/")
            || relativePath.indexOf('\0') >= 0) {
            if (relativePath.isEmpty()) {
                return canonicalRoot;
            }
            throw new FileNotFoundException("Invalid document path in ID");
        }

        // Reject traversal components explicitly, even when normalization would
        // eventually resolve the path back inside the provider root.
        String[] components = relativePath.split("[/\\\\]", -1);
        for (String component : components) {
            if (".".equals(component) || "..".equals(component)) {
                throw new FileNotFoundException("Path traversal is not allowed");
            }
        }

        File target = new File(canonicalRoot, relativePath);
        File canonicalTarget = getCanonicalFileInsideRoot(target);
        if (!canonicalTarget.exists()) {
            throw new FileNotFoundException(
                "Missing file for " + docId + " at " + canonicalTarget);
        }
        return canonicalTarget;
    }

    private File getCanonicalBaseDir() throws FileNotFoundException {
        if (mBaseDir == null) {
            throw new FileNotFoundException("The document provider storage is unavailable");
        }
        try {
            File root = mBaseDir.getCanonicalFile();
            if (!root.exists() || !root.isDirectory()) {
                throw new FileNotFoundException("The document provider root is unavailable");
            }
            return root;
        } catch (IOException e) {
            FileNotFoundException error =
                new FileNotFoundException("Unable to resolve the document provider root");
            error.initCause(e);
            throw error;
        }
    }

    private File getCanonicalFileInsideRoot(File file) throws FileNotFoundException {
        try {
            File root = getCanonicalBaseDir();
            File canonicalFile = file.getCanonicalFile();
            String rootPath = root.getPath();
            String filePath = canonicalFile.getPath();
            if (!filePath.equals(rootPath)
                && !filePath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("Path is outside the document provider root");
            }
            return canonicalFile;
        } catch (IOException e) {
            FileNotFoundException error =
                new FileNotFoundException("Unable to resolve document path");
            error.initCause(e);
            throw error;
        }
    }

    private File getChildFile(File parent, String displayName) throws FileNotFoundException {
        validateDisplayName(displayName);
        File canonicalParent = getCanonicalFileInsideRoot(parent);
        if (!canonicalParent.isDirectory()) {
            throw new FileNotFoundException("Document parent is not a directory");
        }
        return getCanonicalFileInsideRoot(new File(canonicalParent, displayName));
    }

    private static void validateDisplayName(String displayName) throws FileNotFoundException {
        if (displayName == null || displayName.isEmpty()
            || ".".equals(displayName) || "..".equals(displayName)
            || displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0
            || displayName.indexOf('\0') >= 0 || new File(displayName).isAbsolute()) {
            throw new FileNotFoundException("Invalid document name");
        }
    }

}
