package tech.sourced.engine.iterator

import org.apache.spark.internal.Logging
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.{ObjectId, ObjectReader, Repository}
import tech.sourced.engine.util.{CompiledFilter, Filters}

/**
  * Iterator that will return rows of blobs in a repository.
  *
  * @param finalColumns final columns that must be in the resultant row
  * @param repo         repository to get the data from
  * @param prevIter     previous iterator, if the iterator is chained
  * @param filters      filters for the iterator
  */
class BlobIterator(finalColumns: Array[String],
                      repo: Repository,
                      prevIter: TreeEntryIterator,
                      filters: Seq[CompiledFilter])
  extends ChainableIterator[Blob](
    finalColumns,
    Option(prevIter).orNull,
    filters
  ) with Logging {

  /** @inheritdoc */
  override protected def loadIterator(compiledFilters: Seq[CompiledFilter]): Iterator[Blob] = {
    val filters = Filters(compiledFilters)
    val treeEntryIter = Option(prevIter) match {
      case Some(it) =>
        Seq(it.currentRow).toIterator
      case None => GitTreeEntryIterator.loadIterator(
        repo,
        None,
        filters,
        blobIdKey = "blob_id"
      )
    }

    val iter = treeEntryIter.flatMap(entry => {
      if (repo.hasObject(entry.blob)) {
        Some(Blob(entry.blob, entry.commitHash, entry.ref, entry.repo))
      } else {
        None
      }
    })

    if (filters.hasFilters("blob_id")) {
      iter.filter(b => filters.matches(Seq("blob_id"), b.id.getName))
    } else {
      iter
    }
  }

  override protected def mapColumns(blob: Blob): RawRow = {
    val content = BlobIterator.readFile(
      blob.id,
      repo.newObjectReader()
    )

    val isBinary = RawText.isBinary(content)

    Map[String, Any](
      "commit_hash" -> blob.commit.getName,
      "repository_id" -> blob.repo,
      "reference_name" -> blob.ref,
      "blob_id" -> blob.id.getName,
      "content" -> (if (isBinary) Array.emptyByteArray else content),
      "is_binary" -> isBinary
    )
  }

}

case class Blob(id: ObjectId, commit: ObjectId, ref: String, repo: String)

object BlobIterator {
  /** Max bytes to read for the content of a file. */
  val readMaxBytes: Int = 20 * 1024 * 1024

  /**
    * Read max N bytes of the given blob
    *
    * @param objId  ID of the object to read
    * @param reader Object reader to use
    * @param max    maximum number of bytes to read in memory
    * @return Bytearray with the contents of the file
    */
  def readFile(objId: ObjectId, reader: ObjectReader, max: Integer = readMaxBytes): Array[Byte] = {
    val obj = reader.open(objId)
    val data = if (obj.isLarge) {
      val buf = Array.ofDim[Byte](max)
      val is = obj.openStream()
      is.read(buf)
      is.close()
      buf
    } else {
      obj.getBytes
    }
    reader.close()
    data
  }
}
