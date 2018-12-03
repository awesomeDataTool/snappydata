/*
 *
 */
package org.apache.spark.sql.execution.columnar

import java.nio.{ByteBuffer, ByteOrder}
import java.sql.{Connection, ResultSet, Statement}

import scala.util.control.NonFatal

import com.gemstone.gemfire.cache.EntryDestroyedException
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl
import com.gemstone.gemfire.internal.shared.BufferAllocator
import com.koloboke.function.IntObjPredicate
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection
import io.snappydata.collection.IntObjectHashMap
import io.snappydata.thrift.common.BufferedBlob

import org.apache.spark.{Logging, TaskContext}
import org.apache.spark.memory.MemoryManagerCallback.releaseExecutionMemory
import org.apache.spark.sql.execution.columnar.encoding.{ColumnDecoder, ColumnDeleteDecoder, ColumnEncoding, UpdatedColumnDecoder, UpdatedColumnDecoderBase}
import org.apache.spark.sql.execution.columnar.impl.{ColumnDelta, ColumnFormatEntry}
import org.apache.spark.sql.store.CompressionUtils
import org.apache.spark.sql.types.StructField

/*
 * TODO:PS:Review
 * Shifted from the ColumnBatch.scala it also need to be
 * accessed for V2Connector. Added into the shared encoders
 * module.
 */
abstract class ResultSetIterator[A](conn: Connection,
    stmt: Statement, rs: ResultSet, context: TaskContext,
    closeConnectionOnResultsClose: Boolean = true)
    extends Iterator[A] with Logging {

  protected[this] final var doMove = true

  protected[this] final var hasNextValue: Boolean = rs ne null

  if (context ne null) {
    val partitionId = context.partitionId()
    context.addTaskCompletionListener { _ =>
      logDebug(s"closed connection for task from listener $partitionId")
      close()
    }
  }

  override final def hasNext: Boolean = {
    if (doMove && hasNextValue) {
      doMove = false
      hasNextValue = false
      hasNextValue = moveNext()
      hasNextValue
    } else {
      hasNextValue
    }
  }

  protected def moveNext(): Boolean = rs.next()

  override final def next(): A = {
    if (!doMove || hasNext) {
      doMove = true
      getCurrentValue
    } else null.asInstanceOf[A]
  }

  protected def getCurrentValue: A

  def close() {
    // if (!hasNextValue) return
    try {
      if (rs ne null) {
        // GfxdConnectionWrapper.restoreContextStack(stmt, rs)
        // rs.lightWeightClose()
        rs.close()
      }
    } catch {
      case NonFatal(e) => logWarning("Exception closing resultSet", e)
    }
    try {
      if (stmt ne null) {
        stmt.getConnection match {
          case embedConn: EmbedConnection =>
            val lcc = embedConn.getLanguageConnection
            if (lcc ne null) {
              lcc.clearExecuteLocally()
            }
          case _ =>
        }
        stmt.close()
      }
    } catch {
      case NonFatal(e) => logWarning("Exception closing statement", e)
    }
    try {
      if (conn ne null) {
        conn.close()
      }
    } catch {
      case NonFatal(e) => logWarning("Exception closing connection", e)
    }
    hasNextValue = false

  }
}

/*
 * TODO:PS:Review
 * Shifted from the ColumnBatch.scala it also need to be
 * accessed for V2Connector. Added into the shared encoders
 * module.
 */
final class ColumnBatchIteratorOnRS(conn: Connection,
    projection: Array[Int], stmt: Statement, rs: ResultSet,
    context: TaskContext, partitionId: Int)
    extends ResultSetIterator[ByteBuffer](conn, stmt, rs, context) {
  private var currentUUID: Long = _
  // upto three deltas for each column and a deleted mask
  private val totalColumns = (projection.length * (ColumnDelta.MAX_DEPTH + 1)) + 1
  private val allocator = GemFireCacheImpl.getCurrentBufferAllocator
  private var colBuffers: IntObjectHashMap[ByteBuffer] = _
  private var currentStats: ByteBuffer = _
  private var currentDeltaStats: ByteBuffer = _
  private var rsHasNext: Boolean = rs.next()

  def getCurrentBatchId: Long = currentUUID

  def getCurrentBucketId: Int = partitionId

  private def decompress(buffer: ByteBuffer): ByteBuffer = {
    if ((buffer ne null) && buffer.remaining() > 0) {
      val result = CompressionUtils.codecDecompressIfRequired(
        buffer.order(ByteOrder.LITTLE_ENDIAN), allocator)
      if (result ne buffer) {
        BufferAllocator.releaseBuffer(buffer)
        // decompressed buffer will be ordered by LITTLE_ENDIAN while non-decompressed
        // is returned with BIG_ENDIAN in order to distinguish the two cases
        result
      } else result.order(ByteOrder.BIG_ENDIAN)
    } else null // indicates missing value
  }

  private def getBufferFromBlob(blob: java.sql.Blob): ByteBuffer = {
    val buffer = decompress(blob match {
      case blob: BufferedBlob =>
        // the chunk can never be a ByteBufferReference in this case and
        // the internal buffer will now be owned by ColumnFormatValue
        val chunk = blob.getAsLastChunk
        assert(!chunk.isSetChunkReference)
        chunk.chunk
      case _ => ByteBuffer.wrap(blob.getBytes(1, blob.length().asInstanceOf[Int]))
    })
    blob.free()
    buffer
  }

  def getColumnLob(columnIndex: Int): ByteBuffer = {
    val buffer = colBuffers.get(columnIndex + 1)
    if (buffer ne null) buffer
    else {
      // empty buffer indicates value removed from region
      throw new EntryDestroyedException(s"Iteration on column=${columnIndex + 1} " +
          s"bucket=$partitionId uuid=$currentUUID failed due to missing value")
    }
  }

  def getCurrentDeltaStats: ByteBuffer = currentDeltaStats

  def getUpdatedColumnDecoder(decoder: ColumnDecoder, field: StructField,
      columnIndex: Int): UpdatedColumnDecoderBase = {
    if (currentDeltaStats eq null) return null
    val buffers = colBuffers
    val deltaPosition = ColumnDelta.deltaColumnIndex(columnIndex, 0)
    val delta1 = buffers.get(deltaPosition)
    val delta2 = buffers.get(deltaPosition - 1)
    if ((delta1 ne null) || (delta2 ne null)) {
      UpdatedColumnDecoder(decoder, field, delta1, delta2)
    } else null
  }

  def getDeletedColumnDecoder: ColumnDeleteDecoder = {
    colBuffers.get(ColumnFormatEntry.DELETE_MASK_COL_INDEX) match {
      case null => null
      case deleteBuffer => new ColumnDeleteDecoder(deleteBuffer)
    }
  }

  def getDeletedRowCount: Int = {
    val delete = colBuffers.get(ColumnFormatEntry.DELETE_MASK_COL_INDEX)
    if (delete eq null) 0
    else {
      val allocator = ColumnEncoding.getAllocator(delete)
      ColumnEncoding.readInt(allocator.baseObject(delete),
        allocator.baseOffset(delete) + delete.position() + 8)
    }
  }

  private def releaseColumns(): Unit = {
    val buffers = colBuffers
    // not null check in case constructor itself fails due to low memory
    if ((buffers ne null) && buffers.size() > 0) {
      buffers.forEachWhile(new IntObjPredicate[ByteBuffer] {
        override def test(col: Int, buffer: ByteBuffer): Boolean = {
          // release previous set of buffers immediately
          if (buffer ne null) {
            // release from accounting if decompressed buffer
            if (!BufferAllocator.releaseBuffer(buffer) &&
                (buffer.order() eq ByteOrder.LITTLE_ENDIAN)) {
              releaseExecutionMemory(buffer, CompressionUtils.DECOMPRESSION_OWNER)
            }
          }
          true
        }
      })
      colBuffers = null
    }
  }

  private def readColumnData(): Unit = {
    val columnIndex = rs.getInt(3)
    val columnBlob = rs.getBlob(4)
    val columnBuffer = getBufferFromBlob(columnBlob)
    if (columnBuffer ne null) {
      // put all the read buffers in "colBuffers" to free on next() or close()
      colBuffers.justPut(columnIndex, columnBuffer)
      columnIndex match {
        case ColumnFormatEntry.STATROW_COL_INDEX => currentStats = columnBuffer
        case ColumnFormatEntry.DELTA_STATROW_COL_INDEX => currentDeltaStats = columnBuffer
        case _ =>
      }
    }
  }

  override protected def moveNext(): Boolean = {
    currentStats = null
    currentDeltaStats = null
    releaseColumns()
    if (rsHasNext) {
      currentUUID = rs.getLong(1)
      // create a new map instead of clearing old one to help young gen GC
      colBuffers = IntObjectHashMap.withExpectedSize[ByteBuffer](totalColumns + 1)
      // keep reading next till its still part of current column batch; if UUID changes
      // then next call to "moveNext" will read from incremented cursor position
      // else all rows may have been read which is indicated by "rsHasNext"
      do {
        readColumnData()
        rsHasNext = rs.next()
      } while (rsHasNext && rs.getLong(1) == currentUUID)
      true
    } else false
  }

  override protected def getCurrentValue: ByteBuffer = currentStats

  override def close(): Unit = {
    releaseColumns()
    super.close()
  }
}