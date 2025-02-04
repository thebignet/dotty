/* NSC -- new Scala compiler
 * Copyright 2005-2013 LAMP/EPFL
 * @author  Martin Odersky
 */

package dotty.tools
package io

import java.nio.file.{Files, NotDirectoryException}

/** ''Note:  This library is considered experimental and should not be used unless you know what you are doing.'' */
class PlainDirectory(givenPath: Directory) extends PlainFile(givenPath) {
  override def isDirectory = true
  override def iterator() = givenPath.list filter (_.exists) map (x => new PlainFile(x))
  override def delete(): Unit = givenPath.deleteRecursively()
}

/** This class implements an abstract file backed by a File.
 *
 * ''Note:  This library is considered experimental and should not be used unless you know what you are doing.''
 */
class PlainFile(val givenPath: Path) extends AbstractFile {
  assert(path ne null)

  val jpath = givenPath.jpath
  override def underlyingSource = Some(this)

  private val fpath = givenPath.toAbsolute

  /** Returns the name of this abstract file. */
  def name = givenPath.name

  /** Returns the path of this abstract file. */
  def path = givenPath.path

  /** The absolute file. */
  def absolute = new PlainFile(givenPath.toAbsolute)

  override def container: AbstractFile = new PlainFile(givenPath.parent)
  override def input = Files.newInputStream(jpath)
  override def output = Files.newOutputStream(jpath)
  override def sizeOption = Some(givenPath.length.toInt)

  override def hashCode(): Int = fpath.hashCode()
  override def equals(that: Any): Boolean = that match {
    case x: PlainFile => fpath == x.fpath
    case _            => false
  }

  /** Is this abstract file a directory? */
  def isDirectory: Boolean = givenPath.isDirectory

  /** Returns the time that this abstract file was last modified. */
  def lastModified: Long = givenPath.lastModified.toMillis

  /** Returns all abstract subfiles of this abstract directory. */
  def iterator: Iterator[AbstractFile] = {
    try {
      import scala.collection.JavaConverters._
      val it = Files.newDirectoryStream(jpath).iterator()
      it.asScala.map(p => new PlainFile(Path(p)))
    } catch {
      case _: NotDirectoryException => Iterator.empty
    }
  }

  /**
   * Returns the abstract file in this abstract directory with the
   * specified name. If there is no such file, returns null. The
   * argument "directory" tells whether to look for a directory or
   * or a regular file.
   */
  def lookupName(name: String, directory: Boolean): AbstractFile = {
    val child = givenPath / name
    if ((child.isDirectory && directory) || (child.isFile && !directory)) new PlainFile(child)
    else null
  }

  /** Does this abstract file denote an existing file? */
  def create(): Unit = if (!exists) givenPath.createFile()

  /** Delete the underlying file or directory (recursively). */
  def delete(): Unit =
    if (givenPath.isFile) givenPath.delete()
    else if (givenPath.isDirectory) givenPath.toDirectory.deleteRecursively()

  /** Returns a plain file with the given name. It does not
   *  check that it exists.
   */
  def lookupNameUnchecked(name: String, directory: Boolean): AbstractFile =
    new PlainFile(givenPath / name)
}
