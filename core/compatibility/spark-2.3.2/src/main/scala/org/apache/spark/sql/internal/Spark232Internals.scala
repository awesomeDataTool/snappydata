/*
 * Copyright (c) 2017-2020 TIBCO Software Inc. All rights reserved.
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

package org.apache.spark.sql.internal

import java.lang.reflect.Field
import java.net.URI
import java.nio.file.Paths

import scala.collection.mutable

import io.snappydata.Property.HashAggregateSize
import io.snappydata.sql.catalog.SnappyExternalCatalog
import io.snappydata.sql.catalog.impl.SmartConnectorExternalCatalog
import io.snappydata.{HintName, QueryHint}
import org.apache.hadoop.conf.Configuration

import org.apache.spark.deploy.SparkSubmitUtils
import org.apache.spark.internal.config.ConfigBuilder
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry.FunctionBuilder
import org.apache.spark.sql.catalyst.analysis.{Analyzer, FunctionRegistry, UnresolvedRelation, UnresolvedSubqueryColumnAliases, UnresolvedTableValuedFunction}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, AggregateFunction}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeAndComment, CodeGenerator, CodegenContext, ExprCode, GeneratedClass}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeReference, CurrentRow, ExprId, Expression, ExpressionInfo, FrameType, Generator, NamedExpression, NullOrdering, SortDirection, SortOrder, SpecifiedWindowFrame, UnaryMinus, UnboundedFollowing, UnboundedPreceding}
import org.apache.spark.sql.catalyst.json.JSONOptions
import org.apache.spark.sql.catalyst.optimizer.Optimizer
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.sql.catalyst.{AccessUtils, FunctionIdentifier, InternalRow, TableIdentifier}
import org.apache.spark.sql.execution.columnar.ColumnTableScan
import org.apache.spark.sql.execution.command.{ClearCacheCommand, CreateFunctionCommand, CreateTableLikeCommand, DescribeTableCommand, RunnableCommand}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.exchange.{Exchange, ShuffleExchangeExec}
import org.apache.spark.sql.execution.row.RowTableScan
import org.apache.spark.sql.execution.ui.{SQLAppStatusListener, SQLAppStatusStore, SnappySQLAppListener}
import org.apache.spark.sql.execution._
import org.apache.spark.sql.hive._
import org.apache.spark.sql.sources.{BaseRelation, Filter, JdbcExtendedUtils, ResolveQueryHints}
import org.apache.spark.sql.streaming.{LogicalDStreamPlan, StreamingQueryManager}
import org.apache.spark.sql.types.{DataType, Metadata, StructField, StructType}
import org.apache.spark.status.api.v1.RDDStorageInfo
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.SnappyStreamingContext
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.util.Utils
import org.apache.spark.{SparkConf, SparkContext}

/**
 * Implementation of [[SparkInternals]] for Spark 2.3.2.
 */
class Spark232Internals extends Spark211Internals with SparkInternals {

  private val codegenContextClassFunctions: Field = {
    val f = classOf[CodegenContext].getDeclaredField("classFunctions")
    f.setAccessible(true)
    f
  }

  override def version: String = "2.3.2"

  override def registerFunction(session: SparkSession, name: FunctionIdentifier,
      info: ExpressionInfo, function: Seq[Expression] => Expression): Unit = {
    session.sessionState.functionRegistry.registerFunction(name, info, function)
  }

  override def addClassField(ctx: CodegenContext, javaType: String,
      varPrefix: String, initFunc: String => String,
      forceInline: Boolean, useFreshName: Boolean): String = {
    ctx.addMutableState(javaType, varPrefix, initFunc, forceInline, useFreshName)
  }

  override def getInlinedClassFields(ctx: CodegenContext): (Seq[(String, String)], Seq[String]) =
    AccessUtils.getInlinedMutableStates(ctx)

  override def addFunction(ctx: CodegenContext, funcName: String, funcCode: String,
      inlineToOuterClass: Boolean = false): String = {
    ctx.addNewFunction(funcName, funcCode, inlineToOuterClass)
  }

  override def isFunctionAddedToOuterClass(ctx: CodegenContext, funcName: String): Boolean = {
    codegenContextClassFunctions.get(ctx).asInstanceOf[
        mutable.Map[String, mutable.Map[String, String]]].get(ctx.outerClassName) match {
      case Some(m) => m.contains(funcName)
      case None => false
    }
  }

  override def splitExpressions(ctx: CodegenContext, expressions: Seq[String]): String = {
    ctx.splitExpressionsWithCurrentInputs(expressions)
  }

  override def resetCopyResult(ctx: CodegenContext): Unit = {}

  override def isPredicateSubquery(expr: Expression): Boolean = false

  override def copyPredicateSubquery(expr: Expression, newPlan: LogicalPlan,
      newExprId: ExprId): Expression = {
    throw new UnsupportedOperationException(
      s"unexpected copyPredicateSubquery call in Spark $version module")
  }

  // scalastyle:off

  override def columnTableScan(output: Seq[Attribute], dataRDD: RDD[Any],
      otherRDDs: Seq[RDD[InternalRow]], numBuckets: Int, partitionColumns: Seq[Expression],
      partitionColumnAliases: Seq[Seq[Attribute]], baseRelation: PartitionedDataSourceScan,
      relationSchema: StructType, allFilters: Seq[Expression],
      schemaAttributes: Seq[AttributeReference], caseSensitive: Boolean,
      isForSampleReservoirAsRegion: Boolean): ColumnTableScan = {
    new ColumnTableScan23(output, dataRDD, otherRDDs, numBuckets, partitionColumns,
      partitionColumnAliases, baseRelation, relationSchema, allFilters, schemaAttributes,
      caseSensitive, isForSampleReservoirAsRegion)
  }

  // scalastyle:on

  override def rowTableScan(output: Seq[Attribute], schema: StructType, dataRDD: RDD[Any],
      numBuckets: Int, partitionColumns: Seq[Expression],
      partitionColumnAliases: Seq[Seq[Attribute]], table: String,
      baseRelation: PartitionedDataSourceScan, caseSensitive: Boolean): RowTableScan = {
    new RowTableScan23(output, schema, dataRDD, numBuckets, partitionColumns,
      partitionColumnAliases, JdbcExtendedUtils.toLowerCase(table), baseRelation, caseSensitive)
  }

  override def newWholeStagePlan(plan: SparkPlan): WholeStageCodegenExec = {
    WholeStageCodegenExec(plan)(codegenStageId = 0)
  }

  override def newCaseInsensitiveMap(map: Map[String, String]): Map[String, String] = {
    CaseInsensitiveMap[String](map)
  }

  // TODO: SW: inhibit SQLTab attach in SharedState.statusStore and instead do it
  // here for embedded mode in the second call so that security policies are applied to the tab
  def createAndAttachSQLListener(sparkContext: SparkContext): Unit = {
    // SQLAppStatusListener is created in the constructor of SharedState that needs to be overridden
  }

  def createAndAttachSQLListener(state: SharedState): Unit = {
    // replace inside SQLAppStatusStore as well as change on the Spark ListenerBus
    val listenerField = classOf[SQLAppStatusStore].getDeclaredField("listener")
    listenerField.setAccessible(true)
    listenerField.get(state.statusStore).asInstanceOf[Option[SQLAppStatusListener]] match {
      case Some(_: SnappySQLAppListener) => // already changed
      case Some(_: SQLAppStatusListener) =>
        val newListener = new SnappySQLAppListener(state.sparkContext)
        // update on ListenerBus
        state.sparkContext.listenerBus.findListenersByClass[SQLAppStatusListener]().foreach(
          state.sparkContext.removeSparkListener)
        state.sparkContext.listenerBus.addToStatusQueue(newListener)
        listenerField.set(state.statusStore, newListener)
      case _ =>
    }
  }

  def clearSQLListener(): Unit = {
    // no global SQLListener in Spark 2.3.x
  }

  override def createViewSQL(session: SparkSession, plan: LogicalPlan,
      originalText: Option[String]): String = originalText match {
    case Some(viewSQL) => viewSQL
    case None => throw new AnalysisException("Cannot create a persisted VIEW from the Dataset API")
  }

  override def createView(desc: CatalogTable, output: Seq[Attribute],
      child: LogicalPlan): LogicalPlan = View(desc, output, child)

  override def newCreateFunctionCommand(schemaName: Option[String], functionName: String,
      className: String, resources: Seq[FunctionResource], isTemp: Boolean,
      ignoreIfExists: Boolean, replace: Boolean): LogicalPlan = {
    CreateFunctionCommand(schemaName, functionName, className, resources, isTemp,
      ignoreIfExists, replace)
  }

  override def newDescribeTableCommand(table: TableIdentifier,
      partitionSpec: Map[String, String], isExtended: Boolean,
      isFormatted: Boolean): RunnableCommand = {
    if (isFormatted) {
      throw new ParseException(s"DESCRIBE FORMATTED TABLE not supported in Spark $version")
    }
    DescribeTableCommand(table, partitionSpec, isExtended)
  }

  override def newCreateTableLikeCommand(targetIdent: TableIdentifier,
      sourceIdent: TableIdentifier, location: Option[String],
      allowExisting: Boolean): RunnableCommand = {
    CreateTableLikeCommand(targetIdent, sourceIdent, location, allowExisting)
  }

  override def lookupRelation(catalog: SessionCatalog, name: TableIdentifier,
      alias: Option[String]): LogicalPlan = {
    if (alias.isDefined) {
      throw new AnalysisException(s"Spark $version does not support lookupRelation " +
          s"with an alias: alias=$alias, name=$name")
    }
    catalog.lookupRelation(name)
  }

  override def newClearCacheCommand(): LogicalPlan = ClearCacheCommand()

  override def resolveMavenCoordinates(coordinates: String, remoteRepos: Option[String],
      ivyPath: Option[String], exclusions: Seq[String]): String = {
    SparkSubmitUtils.resolveMavenCoordinates(coordinates,
      SparkSubmitUtils.buildIvySettings(remoteRepos, ivyPath), exclusions)
  }

  override def copyAttribute(attr: Attribute)(name: String,
      dataType: DataType, nullable: Boolean, metadata: Metadata,
      exprId: ExprId): AttributeReference = {
    AttributeReference(name = name, dataType = dataType, nullable = nullable, metadata = metadata)(
      exprId, qualifier = attr.qualifier)
  }

  override def withNewChild(insert: InsertIntoTable, newChild: LogicalPlan): InsertIntoTable = {
    insert.copy(query = newChild)
  }

  override def newInsertPlanWithCountOutput(table: LogicalPlan,
      partition: Map[String, Option[String]], child: LogicalPlan,
      overwrite: Boolean, ifNotExists: Boolean): InsertIntoTable = {
    new Insert23(table, partition, child, overwrite, ifNotExists)
  }

  override def getOverwriteOption(insert: InsertIntoTable): Boolean = insert.overwrite

  override def getIfNotExistsOption(insert: InsertIntoTable): Boolean = insert.ifPartitionNotExists

  override def newGroupingSet(groupingSets: Seq[Seq[Expression]],
      groupByExprs: Seq[Expression], child: LogicalPlan,
      aggregations: Seq[NamedExpression]): LogicalPlan = {
    GroupingSets(groupingSets, groupByExprs, child, aggregations)
  }

  override def newUnresolvedRelation(tableIdentifier: TableIdentifier,
      alias: Option[String]): LogicalPlan = alias match {
    case None => UnresolvedRelation(tableIdentifier)
    case Some(a) => SubqueryAlias(a, UnresolvedRelation(tableIdentifier))
  }

  override def unresolvedRelationAlias(u: UnresolvedRelation): Option[String] = None

  override def newSubqueryAlias(alias: String, child: LogicalPlan,
      view: Option[TableIdentifier]): SubqueryAlias = {
    if (view.isDefined) {
      throw new AnalysisException(s"Spark $version does not support creating SubqueryAlias " +
          s"with a VIEW: alias=$alias, view=${view.get}")
    }
    SubqueryAlias(alias, child)
  }

  override def getViewFromAlias(q: SubqueryAlias): Option[TableIdentifier] = None

  override def newAlias(child: Expression, name: String,
      copyAlias: Option[NamedExpression]): Alias = {
    copyAlias match {
      case None => Alias(child, name)()
      case Some(a: Alias) => Alias(child, name)(a.exprId, a.qualifier, a.explicitMetadata)
      case Some(a) => Alias(child, name)(a.exprId, a.qualifier)
    }
  }

  override def newUnresolvedColumnAliases(outputColumnNames: Seq[String],
      child: LogicalPlan): LogicalPlan = {
    if (outputColumnNames.isEmpty) child
    else UnresolvedSubqueryColumnAliases(outputColumnNames, child)
  }

  override def newSortOrder(child: Expression, direction: SortDirection,
      nullOrdering: NullOrdering): SortOrder = {
    SortOrder(child, direction, nullOrdering, Set.empty)
  }

  override def newRepartitionByExpression(partitionExpressions: Seq[Expression],
      numPartitions: Int, child: LogicalPlan): RepartitionByExpression = {
    RepartitionByExpression(partitionExpressions, child, numPartitions)
  }

  override def newUnresolvedTableValuedFunction(functionName: String,
      functionArgs: Seq[Expression], outputNames: Seq[String]): UnresolvedTableValuedFunction = {
    UnresolvedTableValuedFunction(functionName, functionArgs, outputNames)
  }

  override def newFrameBoundary(boundaryType: FrameBoundaryType.Type,
      num: Option[Expression]): Any = {
    boundaryType match {
      case FrameBoundaryType.UnboundedPreceding => UnboundedPreceding
      case FrameBoundaryType.ValuePreceding => UnaryMinus(num.get)
      case FrameBoundaryType.CurrentRow => CurrentRow
      case FrameBoundaryType.UnboundedFollowing => UnboundedFollowing
      case FrameBoundaryType.ValueFollowing => num.get
    }
  }

  override def newSpecifiedWindowFrame(frameType: FrameType, frameStart: Any,
      frameEnd: Any): SpecifiedWindowFrame = {
    SpecifiedWindowFrame(frameType, frameStart.asInstanceOf[Expression],
      frameEnd.asInstanceOf[Expression])
  }

  override def newLogicalPlanWithHints(child: LogicalPlan,
      hints: Map[QueryHint.Type, HintName.Type]): LogicalPlanWithHints = {
    new ResolvedPlanWithHints23(child, hints)
  }

  override def newTableSample(lowerBound: Double, upperBound: Double, withReplacement: Boolean,
      seed: Long, child: LogicalPlan): Sample = {
    Sample(lowerBound, upperBound, withReplacement, seed, child)
  }

  override def isHintPlan(plan: LogicalPlan): Boolean = plan.isInstanceOf[ResolvedHint]

  override def getHints(plan: LogicalPlan): Map[QueryHint.Type, HintName.Type] = plan match {
    case p: ResolvedPlanWithHints23 => p.allHints
    case _: ResolvedHint =>
      // only broadcast supported
      Map(QueryHint.JoinType -> HintName.JoinType_Broadcast)
    case _ => Map.empty
  }

  override def isBroadcastable(plan: LogicalPlan): Boolean = {
    // Spark now uses the UnresolvedHint/ResolvedHint infrastructure and not a fixed flag
    false
  }

  override def newOneRowRelation(): LogicalPlan = OneRowRelation()

  override def newGeneratePlan(generator: Generator, outer: Boolean, qualifier: Option[String],
      generatorOutput: Seq[Attribute], child: LogicalPlan): LogicalPlan = {
    Generate(generator, unrequiredChildIndex = Nil, outer, qualifier, generatorOutput, child)
  }

  override def writeToDataSource(ds: DataSource, mode: SaveMode,
      data: Dataset[Row]): BaseRelation = {
    ds.writeAndRead(mode, data.planWithBarrier, data.planWithBarrier.output.map(_.name),
      data.queryExecution.executedPlan)
  }

  override def newLogicalRelation(relation: BaseRelation,
      expectedOutputAttributes: Option[Seq[AttributeReference]],
      catalogTable: Option[CatalogTable], isStreaming: Boolean): LogicalRelation = {
    val output = expectedOutputAttributes match {
      case None => relation.schema.toAttributes
      case Some(attrs) => attrs
    }
    LogicalRelation(relation, output, catalogTable, isStreaming)
  }

  override def internalCreateDataFrame(session: SparkSession, catalystRows: RDD[InternalRow],
      schema: StructType, isStreaming: Boolean): Dataset[Row] = {
    session.internalCreateDataFrame(catalystRows, schema, isStreaming)
  }

  override def newRowDataSourceScanExec(fullOutput: Seq[Attribute], requiredColumnsIndex: Seq[Int],
      filters: Seq[Filter], handledFilters: Seq[Filter], rdd: RDD[InternalRow],
      metadata: Map[String, String], relation: BaseRelation,
      tableIdentifier: Option[TableIdentifier]): RowDataSourceScanExec = {
    RowDataSourceScanExec(fullOutput, requiredColumnsIndex, filters.toSet, handledFilters.toSet,
      rdd, relation, tableIdentifier)
  }

  private def toURI(uri: String): URI = {
    if (uri.contains("://")) new URI(uri) else new URI("file://" + Paths.get(uri).toAbsolutePath)
  }

  override def newCodegenSparkFallback(child: SparkPlan,
      session: SnappySession): CodegenSparkFallback = {
    new CodegenSparkFallback23(child, session)
  }

  override def newLogicalDStreamPlan(output: Seq[Attribute], stream: DStream[InternalRow],
      streamingSnappy: SnappyStreamingContext): LogicalDStreamPlan = {
    new LogicalDStreamPlan23(output, stream)(streamingSnappy)
  }

  override def newCatalogDatabase(name: String, description: String,
      locationUri: String, properties: Map[String, String]): CatalogDatabase = {
    CatalogDatabase(name, description, toURI(locationUri), properties)
  }

  override def catalogDatabaseLocationURI(database: CatalogDatabase): String =
    database.locationUri.toString

  // scalastyle:off

  override def newCatalogTable(identifier: TableIdentifier, tableType: CatalogTableType,
      storage: CatalogStorageFormat, schema: StructType, provider: Option[String],
      partitionColumnNames: Seq[String], bucketSpec: Option[BucketSpec],
      owner: String, createTime: Long, lastAccessTime: Long, properties: Map[String, String],
      stats: Option[(BigInt, Option[BigInt], Map[String, ColumnStat])],
      viewOriginalText: Option[String], viewText: Option[String],
      comment: Option[String], unsupportedFeatures: Seq[String],
      tracksPartitionsInCatalog: Boolean, schemaPreservesCase: Boolean,
      ignoredProperties: Map[String, String]): CatalogTable = {
    val statistics = stats match {
      case None => None
      case Some(s) => Some(CatalogStatistics(s._1, s._2, s._3))
    }
    CatalogTable(identifier, tableType, storage, schema, provider, partitionColumnNames,
      bucketSpec, owner, createTime, lastAccessTime, createVersion = "", properties, statistics,
      viewText, comment, unsupportedFeatures, tracksPartitionsInCatalog,
      schemaPreservesCase, ignoredProperties)
  }

  // scalastyle:on

  override def catalogTableViewOriginalText(catalogTable: CatalogTable): Option[String] = None

  override def catalogTableIgnoredProperties(catalogTable: CatalogTable): Map[String, String] =
    catalogTable.ignoredProperties

  override def newCatalogTableWithViewOriginalText(catalogTable: CatalogTable,
      viewOriginalText: Option[String]): CatalogTable = catalogTable

  override def newCatalogStorageFormat(locationUri: Option[String], inputFormat: Option[String],
      outputFormat: Option[String], serde: Option[String], compressed: Boolean,
      properties: Map[String, String]): CatalogStorageFormat = {
    locationUri match {
      case None => CatalogStorageFormat(None, inputFormat, outputFormat,
        serde, compressed, properties)
      case Some(uri) => CatalogStorageFormat(Some(toURI(uri)), inputFormat, outputFormat,
        serde, compressed, properties)
    }
  }

  override def catalogStorageFormatLocationUri(
      storageFormat: CatalogStorageFormat): Option[String] = storageFormat.locationUri match {
    case None => None
    case Some(uri) => Some(uri.toString)
  }

  override def catalogTablePartitionToRow(partition: CatalogTablePartition,
      partitionSchema: StructType, defaultTimeZoneId: String): InternalRow = {
    partition.toRow(partitionSchema, defaultTimeZoneId)
  }

  override def loadDynamicPartitions(externalCatalog: ExternalCatalog, schema: String,
      table: String, loadPath: String, partition: TablePartitionSpec, replace: Boolean,
      numDP: Int, holdDDLTime: Boolean): Unit = {
    if (holdDDLTime) {
      throw new UnsupportedOperationException(
        s"unexpected loadDynamicPartitions with holdDDLTime=true in Spark $version module")
    }
    externalCatalog.loadDynamicPartitions(schema, table, loadPath, partition, replace, numDP)
  }

  override def alterTableSchema(externalCatalog: ExternalCatalog, schemaName: String,
      table: String, newSchema: StructType): Unit = {
    externalCatalog.alterTableDataSchema(schemaName, table, newSchema)
  }

  override def alterTableStats(externalCatalog: ExternalCatalog, schema: String, table: String,
      stats: Option[(BigInt, Option[BigInt], Map[String, ColumnStat])]): Unit = {
    val catalogStats = stats match {
      case None => None
      case Some(s) => Some(CatalogStatistics(s._1, s._2, s._3))
    }
    externalCatalog.alterTableStats(schema, table, catalogStats)
  }

  override def alterFunction(externalCatalog: ExternalCatalog, schema: String,
      function: CatalogFunction): Unit = externalCatalog.alterFunction(schema, function)

  override def columnStatToMap(stat: Any, colName: String,
      dataType: DataType): Map[String, String] = {
    stat.asInstanceOf[ColumnStat].toMap(colName, dataType)
  }

  override def newEmbeddedHiveCatalog(conf: SparkConf, hadoopConf: Configuration,
      createTime: Long): SnappyHiveExternalCatalog = {
    new SnappyEmbeddedHiveCatalog23(conf, hadoopConf, createTime)
  }

  override def newSmartConnectorExternalCatalog(
      session: SparkSession): SmartConnectorExternalCatalog = {
    new SmartConnectorExternalCatalog23(session)
  }

  override def lookupDataSource(provider: String, conf: => SQLConf): Class[_] =
    DataSource.lookupDataSource(provider, conf)

  override def newShuffleExchange(newPartitioning: Partitioning, child: SparkPlan): Exchange = {
    ShuffleExchangeExec(newPartitioning, child)
  }

  override def isShuffleExchange(plan: SparkPlan): Boolean = plan.isInstanceOf[ShuffleExchangeExec]

  override def classOfShuffleExchange(): Class[_] = classOf[ShuffleExchangeExec]

  override def getStatistics(plan: LogicalPlan): Statistics = plan.stats

  override def supportsPartial(aggregate: AggregateFunction): Boolean = true

  override def planAggregateWithoutPartial(groupingExpressions: Seq[NamedExpression],
      aggregateExpressions: Seq[AggregateExpression], resultExpressions: Seq[NamedExpression],
      planChild: () => SparkPlan): Seq[SparkPlan] = {
    throw new UnsupportedOperationException(
      s"unexpected planAggregateWithoutPartial call in Spark $version module")
  }

  override def compile(code: CodeAndComment): GeneratedClass = CodeGenerator.compile(code)._1

  override def newJSONOptions(parameters: Map[String, String],
      session: Option[SparkSession]): JSONOptions = session match {
    case None =>
      new JSONOptions(parameters,
        SQLConf.SESSION_LOCAL_TIMEZONE.defaultValue.get,
        SQLConf.COLUMN_NAME_OF_CORRUPT_RECORD.defaultValue.get)
    case Some(sparkSession) =>
      new JSONOptions(parameters,
        sparkSession.sessionState.conf.sessionLocalTimeZone,
        sparkSession.sessionState.conf.columnNameOfCorruptRecord)
  }

  override def newSnappySessionState(snappySession: SnappySession): SnappySessionState = {
    new SnappySessionStateBuilder23(snappySession).build()
  }

  override def newPreWriteCheck(sessionState: SnappySessionState): LogicalPlan => Unit = {
    PreWriteCheck
  }

  override def hiveConditionalStrategies(sessionState: SnappySessionState): Seq[Strategy] = {
    // DataSinks in older Spark releases is now taken care of by HiveAnalysis
    new HiveConditionalStrategy(_.HiveTableScans, sessionState) ::
        new HiveConditionalStrategy(_.Scripts, sessionState) :: Nil
  }

  override def newCacheManager(): CacheManager = new SnappyCacheManager23

  override def buildConf(key: String): ConfigBuilder = SQLConf.buildConf(key)

  override def getCachedRDDInfos(context: SparkContext): Seq[RDDStorageInfo] = {
    context.ui.get.store.rddList()
  }
}

/**
 * Simple extension to CacheManager to enable clearing cached plan on cache create/drop.
 */
final class SnappyCacheManager23 extends CacheManager {

  override def cacheQuery(query: Dataset[_], tableName: Option[String],
      storageLevel: StorageLevel): Unit = {
    super.cacheQuery(query, tableName, storageLevel)
    // clear plan cache since cached representation can change existing plans
    query.sparkSession.asInstanceOf[SnappySession].clearPlanCache()
  }

  override def uncacheQuery(session: SparkSession, plan: LogicalPlan, blocking: Boolean): Unit = {
    super.uncacheQuery(session, plan, blocking)
    session.asInstanceOf[SnappySession].clearPlanCache()
  }

  override def recacheByPlan(session: SparkSession, plan: LogicalPlan): Unit = {
    super.recacheByPlan(session, plan)
    session.asInstanceOf[SnappySession].clearPlanCache()
  }

  override def recacheByPath(session: SparkSession, resourcePath: String): Unit = {
    super.recacheByPath(session, resourcePath)
    session.asInstanceOf[SnappySession].clearPlanCache()
  }
}

final class SnappyEmbeddedHiveCatalog23(override val conf: SparkConf,
    override val hadoopConf: Configuration, override val createTime: Long)
    extends SnappyHiveCatalogBase(conf, hadoopConf) with SnappyHiveExternalCatalog {

  override def getTable(schema: String, table: String): CatalogTable =
    getTableImpl(schema, table)

  override protected def baseCreateDatabase(schemaDefinition: CatalogDatabase,
      ignoreIfExists: Boolean): Unit = super.doCreateDatabase(schemaDefinition, ignoreIfExists)

  override protected def baseDropDatabase(schema: String, ignoreIfNotExists: Boolean,
      cascade: Boolean): Unit = super.doDropDatabase(schema, ignoreIfNotExists, cascade)

  override protected def baseCreateTable(tableDefinition: CatalogTable,
      ignoreIfExists: Boolean): Unit = super.doCreateTable(tableDefinition, ignoreIfExists)

  override protected def baseDropTable(schema: String, table: String, ignoreIfNotExists: Boolean,
      purge: Boolean): Unit = super.doDropTable(schema, table, ignoreIfNotExists, purge)

  override protected def baseAlterTable(tableDefinition: CatalogTable): Unit =
    super.doAlterTable(tableDefinition)

  override protected def baseRenameTable(schema: String, oldName: String, newName: String): Unit =
    super.doRenameTable(schema, oldName, newName)

  override protected def baseLoadDynamicPartitions(schema: String, table: String, loadPath: String,
      partition: TablePartitionSpec, replace: Boolean, numDP: Int, holdDDLTime: Boolean): Unit = {
    SparkSupport.internals.loadDynamicPartitions(this, schema, table, loadPath, partition,
      replace, numDP, holdDDLTime)
  }

  override protected def baseCreateFunction(schema: String,
      funcDefinition: CatalogFunction): Unit = super.doCreateFunction(schema, funcDefinition)

  override protected def baseDropFunction(schema: String, name: String): Unit =
    super.doDropFunction(schema, name)

  override protected def baseRenameFunction(schema: String, oldName: String,
      newName: String): Unit = super.doRenameFunction(schema, oldName, newName)

  override protected def doCreateDatabase(schemaDefinition: CatalogDatabase,
      ignoreIfExists: Boolean): Unit = createDatabaseImpl(schemaDefinition, ignoreIfExists)

  override protected def doDropDatabase(schema: String, ignoreIfNotExists: Boolean,
      cascade: Boolean): Unit = dropDatabaseImpl(schema, ignoreIfNotExists, cascade)

  override def doAlterDatabase(schemaDefinition: CatalogDatabase): Unit =
    alterDatabaseImpl(schemaDefinition)

  override protected def doCreateTable(table: CatalogTable, ignoreIfExists: Boolean): Unit =
    createTableImpl(table, ignoreIfExists)

  override protected def doDropTable(schema: String, table: String, ignoreIfNotExists: Boolean,
      purge: Boolean): Unit = dropTableImpl(schema, table, ignoreIfNotExists, purge)

  override protected def doRenameTable(schema: String, oldName: String, newName: String): Unit =
    renameTableImpl(schema, oldName, newName)

  override def doAlterTable(table: CatalogTable): Unit = alterTableImpl(table)

  override def doAlterTableStats(schema: String, table: String,
      stats: Option[CatalogStatistics]): Unit = {
    withHiveExceptionHandling(super.doAlterTableStats(schema, table, stats))
  }

  override def loadDynamicPartitions(schema: String, table: String, loadPath: String,
      partition: TablePartitionSpec, replace: Boolean, numDP: Int): Unit = {
    loadDynamicPartitionsImpl(schema, table, loadPath, partition, replace, numDP,
      holdDDLTime = false)
  }

  override def listPartitionsByFilter(schema: String, table: String, predicates: Seq[Expression],
      defaultTimeZoneId: String): Seq[CatalogTablePartition] = {
    withHiveExceptionHandling(super.listPartitionsByFilter(schema, table,
      predicates, defaultTimeZoneId))
  }

  override protected def doCreateFunction(schema: String, function: CatalogFunction): Unit =
    createFunctionImpl(schema, function)

  override protected def doDropFunction(schema: String, funcName: String): Unit =
    dropFunctionImpl(schema, funcName)

  override protected def doAlterFunction(schema: String, function: CatalogFunction): Unit = {
    withHiveExceptionHandling(super.doAlterFunction(schema, function))
    SnappySession.clearAllCache()
  }

  override protected def doRenameFunction(schema: String, oldName: String, newName: String): Unit =
    renameFunctionImpl(schema, oldName, newName)
}

final class SmartConnectorExternalCatalog23(override val session: SparkSession)
    extends ExternalCatalog with SmartConnectorExternalCatalog {

  override def getTable(schema: String, table: String): CatalogTable =
    getTableImpl(schema, table)

  override protected def doCreateDatabase(schemaDefinition: CatalogDatabase,
      ignoreIfExists: Boolean): Unit = createDatabaseImpl(schemaDefinition, ignoreIfExists)

  override protected def doDropDatabase(schema: String, ignoreIfNotExists: Boolean,
      cascade: Boolean): Unit = dropDatabaseImpl(schema, ignoreIfNotExists, cascade)

  override protected def doAlterDatabase(schemaDefinition: CatalogDatabase): Unit =
    throw new UnsupportedOperationException("Schema definitions cannot be altered")

  override protected def doCreateTable(table: CatalogTable, ignoreIfExists: Boolean): Unit =
    createTableImpl(table, ignoreIfExists)

  override protected def doDropTable(schema: String, table: String, ignoreIfNotExists: Boolean,
      purge: Boolean): Unit = dropTableImpl(schema, table, ignoreIfNotExists, purge)

  override protected def doRenameTable(schema: String, oldName: String, newName: String): Unit =
    renameTableImpl(schema, oldName, newName)

  override protected def doAlterTable(table: CatalogTable): Unit = alterTableImpl(table)

  override protected def doAlterTableDataSchema(schemaName: String, table: String,
      newSchema: StructType): Unit = alterTableSchemaImpl(schemaName, table, newSchema)

  override protected def doAlterTableStats(schema: String, table: String,
      stats: Option[CatalogStatistics]): Unit = stats match {
    case None => alterTableStatsImpl(schema, table, None)
    case Some(s) => alterTableStatsImpl(schema, table,
      Some((s.sizeInBytes, s.rowCount, s.colStats)))
  }

  override def loadDynamicPartitions(schema: String, table: String, loadPath: String,
      partition: TablePartitionSpec, replace: Boolean, numDP: Int): Unit = {
    loadDynamicPartitionsImpl(schema, table, loadPath, partition, replace, numDP,
      holdDDLTime = false)
  }

  override def listPartitionsByFilter(schema: String, table: String, predicates: Seq[Expression],
      defaultTimeZoneId: String): Seq[CatalogTablePartition] = {
    listPartitionsByFilterImpl(schema, table, predicates, defaultTimeZoneId)
  }

  override protected def doCreateFunction(schema: String, function: CatalogFunction): Unit =
    createFunctionImpl(schema, function)

  override protected def doDropFunction(schema: String, funcName: String): Unit =
    dropFunctionImpl(schema, funcName)

  override protected def doAlterFunction(schema: String, function: CatalogFunction): Unit =
    alterFunctionImpl(schema, function)

  override protected def doRenameFunction(schema: String, oldName: String, newName: String): Unit =
    renameFunctionImpl(schema, oldName, newName)
}

class SnappySessionCatalog23(override val snappySession: SnappySession,
    override val snappyExternalCatalog: SnappyExternalCatalog,
    override val globalTempViewManager: GlobalTempViewManager,
    override val functionResourceLoader: FunctionResourceLoader,
    override val functionRegistry: FunctionRegistry, override val parser: SnappySqlParser,
    override val sqlConf: SQLConf, hadoopConf: Configuration,
    override val wrappedCatalog: Option[SnappySessionCatalog])
    extends SessionCatalog(snappyExternalCatalog, globalTempViewManager, functionRegistry,
      sqlConf, hadoopConf, parser, functionResourceLoader) with SnappySessionCatalog {

  override def functionNotFound(name: String): Nothing = {
    super.failFunctionLookup(FunctionIdentifier(name, None))
  }

  override def newView(table: CatalogTable, child: LogicalPlan): LogicalPlan =
    View(desc = table, output = table.schema.toAttributes, child)

  override def newCatalogRelation(schemaName: String, table: CatalogTable): LogicalPlan =
    UnresolvedCatalogRelation(table)

  override def lookupRelation(name: TableIdentifier): LogicalPlan = lookupRelationImpl(name, None)

  override def registerFunction(funcDefinition: CatalogFunction,
      overrideIfExists: Boolean, functionBuilder: Option[FunctionBuilder]): Unit = {
    val func = funcDefinition.identifier
    if (functionRegistry.functionExists(func) && !overrideIfExists) {
      throw new AnalysisException(s"Function $func already exists")
    }
    val info = new ExpressionInfo(funcDefinition.className, func.database.orNull, func.funcName)
    val builder = functionBuilder.getOrElse {
      val className = funcDefinition.className
      if (!Utils.classIsLoadable(className)) {
        throw new AnalysisException(s"Can not load class '$className' when registering " +
            s"the function '$func', please make sure it is on the classpath")
      }
      makeFunctionBuilderImpl(func.unquotedString, className)
    }
    functionRegistry.registerFunction(func, info, builder)
  }
}

class SnappySessionStateBuilder23(session: SnappySession, parentState: Option[SessionState] = None)
    extends BaseSessionStateBuilder(session, parentState) {

  self =>

  override protected lazy val conf: SQLConf = {
    val conf = parentState.map(_.conf.clone()).getOrElse(new SnappyConf(session))
    mergeSparkConf(conf, session.sparkContext.conf)
    conf
  }

  override protected lazy val sqlParser: SnappySqlParser = session.contextFunctions.newSQLParser()

  protected val externalCatalog: SnappyExternalCatalog =
    session.sharedState.getExternalCatalogInstance(session)

  override protected lazy val resourceLoader: SessionResourceLoader = externalCatalog match {
    case c: SnappyHiveExternalCatalog => new HiveSessionResourceLoader(session, c.client())
    case _ => new SessionResourceLoader(session)
  }

  private def createCatalog(wrapped: Option[SnappySessionCatalog]): SnappySessionCatalog = {
    val catalog = new SnappySessionCatalog23(
      session,
      externalCatalog,
      session.sharedState.globalTempViewManager,
      resourceLoader,
      functionRegistry,
      sqlParser,
      conf,
      SessionState.newHadoopConf(session.sparkContext.hadoopConfiguration, conf),
      wrapped)
    parentState.foreach(_.catalog.copyStateTo(catalog))
    catalog
  }

  override protected lazy val catalog: SnappySessionCatalog = createCatalog(wrapped = None)

  override protected def analyzer: Analyzer = new Analyzer(catalog, conf) with SnappyAnalyzer {

    aSelf =>

    override def session: SnappySession = self.session

    private def state: SnappySessionState = session.sessionState

    override val extendedResolutionRules: Seq[Rule[LogicalPlan]] = {
      (new HiveConditionalRule(_ => new ResolveHiveSerdeTable(state.hiveSession), state) ::
          new PreprocessTable(state) ::
          state.ResolveAliasInGroupBy ::
          new FindDataSourceTable(session) ::
          new ResolveSQLOnFile(session) ::
          session.contextFunctions.getExtendedResolutionRules) ++ customResolutionRules
    }

    override val postHocResolutionRules: Seq[Rule[LogicalPlan]] = {
      (new HiveConditionalRule(_ => new DetermineTableStats(session), state) ::
          new HiveConditionalRule(s =>
            RelationConversions(s.conf, s.catalog.asInstanceOf[HiveSessionCatalog]), state) ::
          PreprocessTableCreation(session) ::
          PreprocessTableInsertion(conf) ::
          DataSourceAnalysis(conf) ::
          new HiveConditionalRule(_ => HiveAnalysis, state) ::
          state.AnalyzeMutableOperations(session, analyzer) ::
          ResolveQueryHints(session) ::
          state.RowLevelSecurity ::
          state.ExternalRelationLimitFetch ::
          session.contextFunctions.getPostHocResolutionRules) ++ customPostHocResolutionRules
    }

    override val extendedCheckRules: Seq[LogicalPlan => Unit] =
      state.getExtendedCheckRules ++ (PreReadCheck +: customCheckRules)

    override lazy val baseAnalyzerInstance: Analyzer = new Analyzer(catalog, conf) {
      override val extendedResolutionRules: Seq[Rule[LogicalPlan]] = aSelf.extendedResolutionRules
      override val postHocResolutionRules: Seq[Rule[LogicalPlan]] = aSelf.postHocResolutionRules
      override val extendedCheckRules: Seq[LogicalPlan => Unit] = aSelf.extendedCheckRules

      override def execute(plan: LogicalPlan): LogicalPlan = aSelf.execute(plan)
    }
  }

  override protected def optimizer: Optimizer = {
    new SparkOptimizer(catalog, experimentalMethods) with DefaultOptimizer {

      override def state: SnappySessionState = session.sessionState

      override def extendedOperatorOptimizationRules: Seq[Rule[LogicalPlan]] =
        super.extendedOperatorOptimizationRules ++ customOperatorOptimizationRules
    }
  }

  override protected def streamingQueryManager: StreamingQueryManager = {
    // Disabling `SnappyAggregateStrategy` for streaming queries as it clashes with
    // `StatefulAggregationStrategy` which is applied by spark for streaming queries. This
    // implies that Snappydata aggregation optimisation will be turned off for any usage of
    // this session including non-streaming queries.

    HashAggregateSize.set(session.sessionState.conf, "-1")
    new StreamingQueryManager(session)
  }

  override def build(): SnappySessionState = {
    new SessionState(session.sharedState, conf, experimentalMethods,
      functionRegistry, udfRegistration, () => catalog, sqlParser,
      () => analyzer, () => optimizer, planner, streamingQueryManager,
      listenerManager, () => resourceLoader, createQueryExecution,
      createClone) with SnappySessionState {

      override val snappySession: SnappySession = session

      override def catalogBuilder(wrapped: Option[SnappySessionCatalog]): SessionCatalog = {
        wrapped match {
          case None => self.catalog
          case _ => self.createCatalog(wrapped)
        }
      }

      def analyzerBuilder(): Analyzer = self.analyzer

      def optimizerBuilder(): Optimizer = self.optimizer
    }
  }

  override protected def newBuilder: NewBuilder = (session, optState) =>
    new SnappySessionStateBuilder23(session.asInstanceOf[SnappySession], optState)
}

final class CodegenSparkFallback23(child: SparkPlan,
    session: SnappySession) extends CodegenSparkFallback(child, session) {

  override def generateTreeString(depth: Int, lastChildren: Seq[Boolean], builder: StringBuilder,
      verbose: Boolean, prefix: String, addSuffix: Boolean): StringBuilder = {
    child.generateTreeString(depth, lastChildren, builder, verbose, prefix, addSuffix)
  }
}

final class LogicalDStreamPlan23(output: Seq[Attribute],
    stream: DStream[InternalRow])(streamingSnappy: SnappyStreamingContext)
    extends LogicalDStreamPlan(output, stream)(streamingSnappy) {

  override def stats: Statistics = Statistics(
    sizeInBytes = BigInt(streamingSnappy.snappySession.sessionState.conf.defaultSizeInBytes)
  )
}