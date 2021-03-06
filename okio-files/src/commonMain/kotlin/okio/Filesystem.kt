/*
 * Copyright (C) 2020 Square, Inc.
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
package okio

abstract class Filesystem {
  /**
   * Resolves [path] against the current working directory and symlinks in this filesystem. The
   * returned path identifies the same file as [path], but with an absolute path that does not
   * include any symbolic links.
   *
   * This is similar to `File.getCanonicalFile()` on the JVM and `realpath` on POSIX. Unlike
   * `File.getCanonicalFile()`, this throws if the file doesn't exist.
   *
   * @throws IOException if [path] cannot be resolved. This will occur if the file doesn't exist,
   *     if the current working directory doesn't exist or is inaccessible, or if another failure
   *     occurs while resolving the path.
   */
  @Throws(IOException::class)
  abstract fun canonicalize(path: Path): Path

  /**
   * Returns metadata of the file, directory, or object identified by [path].
   *
   * @throws IOException if [path] does not exist or its metadata cannot be read.
   */
  @Throws(IOException::class)
  abstract fun metadata(path: Path): FileMetadata

  /**
   * Returns the children of the directory identified by [dir].
   *
   * @throws IOException if [dir] does not exist, is not a directory, or cannot be read. A directory
   *     cannot be read if the current process doesn't have access to [dir], or if there's a loop of
   *     symbolic links, or if any name is too long.
   */
  @Throws(IOException::class)
  abstract fun list(dir: Path): List<Path>

  /**
   * Returns a source that reads the bytes of [file] from beginning to end.
   *
   * @throws IOException if [file] does not exist, is not a file, or cannot be read. A file cannot
   *     be read if the current process doesn't have access to [file], if there's a loop of symbolic
   *     links, or if any name is too long.
   */
  @Throws(IOException::class)
  abstract fun source(file: Path): Source

  /**
   * Returns a sink that writes bytes to [file] from beginning to end. If [file] already exists it
   * will be replaced with the new data.
   *
   * @throws IOException if [file] cannot be written. A file cannot be written if its enclosing
   *     directory does not exist, if the current process doesn't have access to [file], if there's
   *     a loop of symbolic links, or if any name is too long.
   */
  @Throws(IOException::class)
  abstract fun sink(file: Path): Sink

  /**
   * Creates a directory at the path identified by [dir].
   *
   * @throws IOException if [dir]'s parent does not exist, is not a directory, or cannot be written.
   *     A directory cannot be created if the current process doesn't have access, if there's a
   *     loop of symbolic links, or if any name is too long.
   */
  @Throws(IOException::class)
  abstract fun createDirectory(dir: Path)

  /**
   * Moves [source] to [target] in-place if the underlying file system supports it. If [target]
   * exists, it is first removed. If `source == target`, this operation does nothing. This may be
   * used to move a file or a directory.
   *
   * **Only as Atomic as the Underlying Filesystem Supports**
   *
   * FAT and NTFS filesystems cannot atomically move a file over an existing file. If the target
   * file already exists, the move is performed into two steps:
   *
   *  1. Atomically delete the target file.
   *  2. Atomically rename the source file to the target file.
   *
   * The delete step and move step are each atomic but not atomic in aggregate! If this process
   * crashes, the host operating system crashes, or the hardware fails it is possible that the
   * delete step will succeed and the rename will not.
   *
   * **Entire-file or nothing**
   *
   * These are the possible results of this operation:
   *
   *  * This operation returns normally, the source file is absent, and the target file contains the
   *    data previously held by the source file. This is the success case.
   *
   *  * The operation throws an [IOException] and the filesystem is unchanged. For example, this
   *    occurs if this process lacks permissions to perform the move.
   *
   *  * This operation throws an [IOException], the target file is deleted, but the source file is
   *    unchanged. This is the partial failure case described above and is only possible on
   *    filesystems like FAT and NTFS that do not support atomic file replacement. Typically in such
   *    cases this operation won't return at all because the process or operating system has also
   *    crashed.
   *
   * There is no failure mode where the target file holds a subset of the bytes of the source file.
   * If the rename step cannot be performed atomically, this function will throw an [IOException]
   * before attempting a move. Typically this occurs if the source and target files are on different
   * physical volumes.
   *
   * **Non-Atomic Moves**
   *
   * If you need to move files across volumes, use [copy] followed by [delete], and change your
   * application logic to recover should the copy step suffer a partial failure.
   *
   * @throws IOException if the move cannot be performed, or cannot be performed atomically. Moves
   *     fail if the source doesn't exist, if the target is not writable, if the target already
   *     exists and cannot be replaced, or if the move would cause physical or quota limits to be
   *     exceeded. This list of potential problems is not exhaustive.
   */
  @Throws(IOException::class)
  abstract fun atomicMove(source: Path, target: Path)

  /**
   * Copies all of the bytes from the file at [source] to the file at [target]. This does not copy
   * file metadata like last modified time, permissions, or extended attributes.
   *
   * This function is not atomic; a failure may leave [target] in an inconsistent state. For
   * example, [target] may be empty or contain only a prefix of [source].
   *
   * @throws IOException if [source] cannot be read or if [target] cannot be written.
   */
  @Throws(IOException::class)
  open fun copy(source: Path, target: Path) {
    source(source).use { bytesIn ->
      sink(target).buffer().use { bytesOut ->
        bytesOut.writeAll(bytesIn)
      }
    }
  }

  /**
   * Deletes the file or directory at [path].
   *
   * @throws IOException if there is nothing at [path] to delete, or if there is a file or directory
   *     but it could not be deleted. Deletes fail if the current process doesn't have access, if
   *     the filesystem is readonly, or if [path] is a non-empty directory. This list of potential
   *     problems is not exhaustive.
   */
  @Throws(IOException::class)
  abstract fun delete(path: Path)

  companion object {
    /**
     * The current process's host filesystem. Use this instance directly, or dependency inject a
     * [Filesystem] to make code testable.
     */
    val SYSTEM: Filesystem
      get() = PLATFORM_FILESYSTEM

    /**
     * Returns a writable temporary directory on [SYSTEM].
     *
     * This is platform-specific.
     *
     *  * **JVM and Android**: the path in the `java.io.tmpdir` system property
     *  * **Linux, iOS, and macOS**: the path in the `TMPDIR` environment variable.
     *  * **Windows**: the first non-null of `TEMP`, `TMP`, and `USERPROFILE` environment variables.
     */
    val SYSTEM_TEMPORARY_DIRECTORY: Path
      get() = PLATFORM_TEMPORARY_DIRECTORY
  }
}
