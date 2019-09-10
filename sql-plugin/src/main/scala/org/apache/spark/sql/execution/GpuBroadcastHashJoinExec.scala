/*
 * Copyright (c) 2019, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import ai.rapids.spark._
import ai.rapids.spark.GpuMetricNames._

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.physical.{BroadcastDistribution, Distribution, UnspecifiedDistribution}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec
import org.apache.spark.sql.execution.joins._
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.vectorized.ColumnarBatch

class GpuBroadcastHashJoinMeta(
    join: BroadcastHashJoinExec,
    conf: RapidsConf,
    parent: Option[RapidsMeta[_, _, _]],
    rule: ConfKeysAndIncompat)
  extends SparkPlanMeta[BroadcastHashJoinExec](join, conf, parent, rule) {

  val leftKeys: Seq[ExprMeta[_]] = join.leftKeys.map(GpuOverrides.wrapExpr(_, conf, Some(this)))
  val rightKeys: Seq[ExprMeta[_]] = join.rightKeys.map(GpuOverrides.wrapExpr(_, conf, Some(this)))
  val condition: Option[ExprMeta[_]] = join.condition.map(GpuOverrides.wrapExpr(_, conf, Some(this)))

  override val childExprs: Seq[ExprMeta[_]] = leftKeys ++ rightKeys ++ condition

  override def tagPlanForGpu(): Unit = {
    if (!GpuHashJoin.isJoinTypeAllowed(join.joinType)) {
      willNotWorkOnGpu(s" ${join.joinType} is not currently supported")
    }

    val buildSide = join.buildSide match {
      case BuildLeft => childPlans(0)
      case BuildRight => childPlans(1)
    }

    if (!buildSide.canThisBeReplaced) {
      willNotWorkOnGpu("the broadcast for this join must be on the GPU too")
    }

    if (!canThisBeReplaced) {
      buildSide.willNotWorkOnGpu("the BroadcastHashJoin this feeds is not on the GPU")
    }
  }

  override def convertToGpu(): GpuExec = {
    val left = childPlans(0).convertIfNeeded()
    val right = childPlans(1).convertIfNeeded()
    // The broadcast part of this must be a BroadcastExchangeExec
    val buildSide = join.buildSide match {
      case BuildLeft => left
      case BuildRight => right
    }
    if (!buildSide.isInstanceOf[GpuBroadcastExchangeExec]) {
      throw new IllegalStateException("the broadcast must be on the GPU too")
    }
    GpuBroadcastHashJoinExec(
      leftKeys.map(_.convertToGpu()),
      rightKeys.map(_.convertToGpu()),
      join.joinType, join.buildSide,
      condition.map(_.convertToGpu()),
      left, right)
  }
}

case class GpuBroadcastHashJoinExec(
    leftKeys: Seq[GpuExpression],
    rightKeys: Seq[GpuExpression],
    joinType: JoinType,
    buildSide: BuildSide,
    condition: Option[GpuExpression],
    left: SparkPlan,
    right: SparkPlan) extends BinaryExecNode with GpuHashJoin {

  override val additionalMetrics: Map[String, SQLMetric] = Map(
    "joinOutputRows" -> SQLMetrics.createMetric(sparkContext, "join output rows"),
    "joinTime" -> SQLMetrics.createNanoTimingMetric(sparkContext, "join time"),
    "filterTime" -> SQLMetrics.createNanoTimingMetric(sparkContext, "filter time"))

  override def requiredChildDistribution: Seq[Distribution] = {
    val mode = HashedRelationBroadcastMode(buildKeys)
    buildSide match {
      case BuildLeft =>
        BroadcastDistribution(mode) :: UnspecifiedDistribution :: Nil
      case BuildRight =>
        UnspecifiedDistribution :: BroadcastDistribution(mode) :: Nil
    }
  }

  def broadcastExchange: GpuBroadcastExchangeExec = buildPlan match {
    case gpu: GpuBroadcastExchangeExec => gpu
    case reused: ReusedExchangeExec => reused.child.asInstanceOf[GpuBroadcastExchangeExec]
  }

  override def doExecute(): RDD[InternalRow] =
    throw new IllegalStateException("GpuBroadcastHashJoin does not support row-based processing")

  override def doExecuteColumnar() : RDD[ColumnarBatch] = {
    val numOutputRows = longMetric(NUM_OUTPUT_ROWS)
    val numOutputBatches = longMetric(NUM_OUTPUT_BATCHES)
    val totalTime = longMetric(TOTAL_TIME)
    val joinTime = longMetric("joinTime")
    val filterTime = longMetric("filterTime")
    val joinOutputRows = longMetric("joinOutputRows")

    val broadcastRelation = broadcastExchange
      .executeColumnarBroadcast[SerializableGpuColumnarBatch]()

    val boundCondition = condition.map(GpuBindReferences.bindReference(_, output))

    val rdd = streamedPlan.executeColumnar()
    rdd.mapPartitions(it => new Iterator[ColumnarBatch] {
      @transient private lazy val builtTable = {
        // TODO clean up intermediate results...
        val keys = GpuProjectExec.project(broadcastRelation.value.batch, gpuBuildKeys)
        val combined = combine(keys, broadcastRelation.value.batch)
        val asStringCat = GpuColumnVector.convertToStringCategoriesIfNeeded(combined)
        val ret = GpuColumnVector.from(asStringCat)
        // Don't warn for a leak, because we cannot control when we are done with this
        (0 until ret.getNumberOfColumns).foreach(ret.getColumn(_).noWarnLeakExpected())
        ret
      }

      override def hasNext: Boolean = it.hasNext

      override def next(): ColumnarBatch = {
        val cb = it.next()
        val startTime = System.nanoTime()
        val ret = doJoin(builtTable, cb, boundCondition, numOutputRows, numOutputBatches,
          joinOutputRows, joinTime, filterTime)
        totalTime += (System.nanoTime() - startTime)
        ret
      }
    })
  }
}