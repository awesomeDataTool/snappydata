/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package io.snappydata

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions
import scala.util.control.NonFatal

import com.gemstone.gemfire.CancelException
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.ui.{SnappyExternalTableStats, SnappyIndexStats, SnappyRegionStats}
import io.snappydata.Constant.{DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL, PROPERTY_PREFIX, SPARK_SNAPPY_PREFIX}

import org.apache.spark.sql.SnappySession
import org.apache.spark.sql.collection.Utils
import org.apache.spark.{Logging, SparkContext, SparkEnv}

trait TableStatsProviderService extends Logging {

  @volatile
  protected var tableSizeInfo = Map.empty[String, SnappyRegionStats]
  private var externalTableSizeInfo = Map.empty[String, SnappyExternalTableStats]
  @volatile
  private var indexesInfo = Map.empty[String, SnappyIndexStats]
  protected val membersInfo: mutable.Map[String, mutable.Map[String, Any]] =
    new ConcurrentHashMap[String, mutable.Map[String, Any]](8, 0.7f, 1).asScala

  protected[snappydata] lazy val delayMillis: Long = SparkEnv.get match {
    case null => DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL
    case env => env.conf.getOption(PROPERTY_PREFIX + "calcTableSizeInterval") match {
      case None => env.conf.getLong(SPARK_SNAPPY_PREFIX + "calcTableSizeInterval",
        DEFAULT_CALC_TABLE_SIZE_SERVICE_INTERVAL)
      case Some(v) => v.toLong
    }
  }

  @GuardedBy("this")
  protected var memberStatsFuture: Option[Future[Unit]] = None
  protected val waitDuration = Duration(5000L, TimeUnit.MILLISECONDS)

  @volatile protected var doRun: Boolean = false
  @volatile private var running: Boolean = false

  def start(sc: SparkContext, url: String): Unit

  protected def aggregateStats(): Unit = synchronized {
    try {
      if (doRun) {
        val prevTableSizeInfo = tableSizeInfo
        running = true
        try {
          val (tableStats, indexStats, extTableStats) = getAggregatedStatsOnDemand
          tableSizeInfo = tableStats
          indexesInfo = indexStats // populating indexes stats
          externalTableSizeInfo = extTableStats

          // Commenting this call to avoid periodic refresh of members stats
          // get members details
          // fillAggregatedMemberStatsOnDemand()

        } finally {
          running = false
          notifyAll()
        }
        // check if there has been a substantial change in table
        // stats, and clear the plan cache if so
        if (prevTableSizeInfo.size != tableSizeInfo.size) {
          SnappySession.clearAllCache(onlyQueryPlanCache = true)
        } else {
          val prevTotalRows = prevTableSizeInfo.values.map(_.getRowCount).sum
          val newTotalRows = tableSizeInfo.values.map(_.getRowCount).sum
          if (math.abs(newTotalRows - prevTotalRows) > 0.1 * prevTotalRows) {
            SnappySession.clearAllCache(onlyQueryPlanCache = true)
          }
        }
      }
    } catch {
      case _: CancelException => // ignore
      case e: Exception => if (!e.getMessage.contains(
        "com.gemstone.gemfire.cache.CacheClosedException")) {
        logWarning(e.getMessage, e)
      } else {
        logError(e.getMessage, e)
      }
    }
  }

  def fillAggregatedMemberStatsOnDemand(): Unit = {
  }

  def getMembersStatsOnDemand: mutable.Map[String, mutable.Map[String, Any]] = {
    // wait for updated stats for sometime else return the previous information
    val future = synchronized(memberStatsFuture match {
      case Some(f) => f
      case None =>
        implicit val executionContext = Utils.executionContext(Misc.getGemFireCacheNoThrow)
        val f = Future(fillAggregatedMemberStatsOnDemand())
        memberStatsFuture = Some(f)
        f
    })
    try {
      logDebug(s"Obtaining updated Members Statistics. Waiting for $waitDuration")
      Await.result(future, waitDuration)
      synchronized(memberStatsFuture = None)
    } catch {
      case NonFatal(_) => // ignore timeout exception and return current map
        logWarning("Obtaining updated Members Statistics is taking longer than expected time.")
    }
    membersInfo
  }

  def stop(): Unit = {
    doRun = false
    // wait for it to end for sometime
    synchronized {
      if (running) wait(10000)
    }
  }

  def getIndexesStatsFromService: Map[String, SnappyIndexStats] = {
    // TODO: [SachinK] This code is commented to avoid forced refresh of stats
    // on every call (as indexesInfo could be empty).
    /*
    val indexStats = this.indexesInfo
    if (indexStats.isEmpty) {
      // force run
      aggregateStats()
    }
    */
    indexesInfo
  }

  def refreshAndGetTableSizeStats: Map[String, SnappyRegionStats] = {
    // force run
    aggregateStats()
    tableSizeInfo
  }

  def getTableStatsFromService(
      fullyQualifiedTableName: String): Option[SnappyRegionStats] = {
    val tableSizes = this.tableSizeInfo
    if (tableSizes.isEmpty || !tableSizes.contains(fullyQualifiedTableName)) {
      // force run
      aggregateStats()
    }
    tableSizeInfo.get(fullyQualifiedTableName)
  }

  def getExternalTableStatsFromService(
      fullyQualifiedTableName: String): Option[SnappyExternalTableStats] = {
    externalTableSizeInfo.get(fullyQualifiedTableName)
  }

  def getAggregatedStatsOnDemand: (Map[String, SnappyRegionStats],
      Map[String, SnappyIndexStats], Map[String, SnappyExternalTableStats]) = {
    if (!doRun) return (Map.empty, Map.empty, Map.empty)
    val (tableStats, indexStats, externalTableStats) = getStatsFromAllServers()

    val aggregatedStats = scala.collection.mutable.Map[String, SnappyRegionStats]()
    val aggregatedExtTableStats = scala.collection.mutable.Map[String, SnappyExternalTableStats]()
    val aggregatedStatsIndex = scala.collection.mutable.Map[String, SnappyIndexStats]()
    if (!doRun) return (Map.empty, Map.empty, Map.empty)
    // val samples = getSampleTableList(snc)
    tableStats.foreach { stat =>
      aggregatedStats.get(stat.getTableName) match {
        case Some(oldRecord) =>
          aggregatedStats.put(stat.getTableName, oldRecord.getCombinedStats(stat))
        case None =>
          aggregatedStats.put(stat.getTableName, stat)
      }
    }

    indexStats.foreach { stat =>
      aggregatedStatsIndex.put(stat.getIndexName, stat)
    }
    externalTableStats.foreach { stat =>
      aggregatedExtTableStats.put(stat.getTableName, stat)
    }
    (Utils.immutableMap(aggregatedStats),
        Utils.immutableMap(aggregatedStatsIndex),
        Utils.immutableMap(aggregatedExtTableStats))
  }

  /*
  private def getSampleTableList(snc: SnappyContext): Seq[String] = {
    try {
      snc.sessionState.catalog
          .getDataSourceTables(Seq(ExternalTableType.Sample)).map(_.toString())
    } catch {
      case tnfe: org.apache.spark.sql.TableNotFoundException =>
        Nil
    }
  }
  */

  def getStatsFromAllServers(sc: Option[SparkContext] = None): (Seq[SnappyRegionStats],
      Seq[SnappyIndexStats], Seq[SnappyExternalTableStats])
}