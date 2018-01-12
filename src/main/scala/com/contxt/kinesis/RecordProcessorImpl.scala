package com.contxt.kinesis

import akka.Done
import akka.stream.{ KillSwitch, QueueOfferResult }
import akka.stream.scaladsl.SourceQueueWithComplete
import com.amazonaws.services.kinesis.clientlibrary.exceptions.{ KinesisClientLibDependencyException, KinesisClientLibRetryableException, ShutdownException, ThrottlingException }
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorCheckpointer
import com.amazonaws.services.kinesis.clientlibrary.interfaces.v2.{ IRecordProcessor, IShutdownNotificationAware }
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.ShutdownReason
import com.amazonaws.services.kinesis.clientlibrary.types._
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import scala.collection.immutable.Queue
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal
import scala.collection.JavaConversions._

private[kinesis] class ShardCheckpointTracker(shardCheckpointConfig: ShardCheckpointConfig) {
  private var inFlightRecords = Queue.empty[KinesisRecord]
  private var lastCheckpointedAt = ZonedDateTime.now()
  private var lastCompletedButNotCheckpointed = Option.empty[KinesisRecord]
  private var completedButNotCheckpointedCount = 0

  def watchForCompletion(records: Iterable[KinesisRecord]): Unit = {
    inFlightRecords ++= records
  }

  def shouldCheckpoint: Boolean = {
    popCompletedRecords()

    completedButNotCheckpointedCount >= shardCheckpointConfig.checkpointAfterCompletingNrOfRecords ||
      durationSinceLastCheckpoint() >= shardCheckpointConfig.checkpointPeriod
  }

  def checkpointLastCompletedRecord(checkpointLogic: KinesisRecord => Unit): Unit = {
    popCompletedRecords()

    lastCompletedButNotCheckpointed.foreach { kinesisRecord =>
      try {
        checkpointLogic(kinesisRecord)
        lastCompletedButNotCheckpointed = None
      }
      finally {
        clearCheckpointTriggers()
      }
    }
  }

  def allInFlightRecordsCompeted: Boolean = inFlightRecords.forall(_.completionFuture.isCompleted)

  def allInFlightRecordsCompetedFuture(implicit ec: ExecutionContext): Future[Done] = {
    Future
      .sequence(inFlightRecords.map(_.completionFuture))
      .map(_ => Done)
  }

  private def popCompletedRecords(): Unit = {
    val completedRecords = inFlightRecords.takeWhile(_.completionFuture.isCompleted)
    inFlightRecords = inFlightRecords.drop(completedRecords.size)
    completedButNotCheckpointedCount += completedRecords.size
    lastCompletedButNotCheckpointed = completedRecords.lastOption.orElse(lastCompletedButNotCheckpointed)
  }

  private def clearCheckpointTriggers(): Unit = {
    completedButNotCheckpointedCount = 0
    lastCheckpointedAt = ZonedDateTime.now()
  }

  private def durationSinceLastCheckpoint(): Duration = {
    java.time.Duration.between(lastCheckpointedAt, ZonedDateTime.now()).toMillis.millis
  }
}

private[kinesis] class RecordProcessorImpl(
  kinesisAppId: KinesisAppId,
  streamKillSwitch: KillSwitch,
  streamTerminationFuture: Future[Done],
  queue: SourceQueueWithComplete[IndexedSeq[KinesisRecord]],
  shardCheckpointConfig: ShardCheckpointConfig,
  consumerStats: ConsumerStats
) extends IRecordProcessor with IShutdownNotificationAware {
  private val log = LoggerFactory.getLogger(getClass)

  private val shardCheckpointTracker = new ShardCheckpointTracker(shardCheckpointConfig)
  private var shardId: String = _
  private lazy val shardConsumerId = ShardConsumerId(kinesisAppId, shardId)

  override def initialize(initializationInput: InitializationInput): Unit = {
    shardId = initializationInput.getShardId
    val offsetString = getOffsetString(initializationInput.getExtendedSequenceNumber)
    consumerStats.reportInitialization(shardConsumerId)
    log.info(s"Starting $shardConsumerId at $offsetString.")
  }

  override def processRecords(processRecordsInput: ProcessRecordsInput): Unit = {
    try {
      val records = processRecordsInput.getRecords.toIndexedSeq
      val kinesisRecords = records.map(KinesisRecord.fromMutableRecord)
      shardCheckpointTracker.watchForCompletion(kinesisRecords)
      if (kinesisRecords.nonEmpty) blockToEnqueueAndHandleResult(kinesisRecords)
      if (shardCheckpointTracker.shouldCheckpoint) checkpointAndHandleErrors(processRecordsInput.getCheckpointer)
    }
    catch {
      case NonFatal(e) =>
        log.error("Unhandled exception in `processRecords()`, failing the streaming...", e)
        streamKillSwitch.abort(e)
    }
  }

  override def shutdown(shutdownInput: ShutdownInput): Unit = {
    val shutdownReason = shutdownInput.getShutdownReason

    shutdownReason match {
      case ShutdownReason.ZOMBIE =>
        // Do nothing.

      case ShutdownReason.TERMINATE =>
        waitForInFlightRecordsOrTermination()
        checkpointAndHandleErrors(shutdownInput.getCheckpointer, shardEnd = true)

      case ShutdownReason.REQUESTED =>
        /* The shutdown can be requested due to a stream failure or downstream cancellation. In either of these cases,
         * some records will never be processed, so we should not block waiting for all the records to complete.
         * Additionally, when we know the stream failed, and is being torn down, it's pointless to wait for in-flight
         * records to complete. */
        waitForInFlightRecordsUnlessStreamFailed(shardCheckpointConfig.maxWaitForCompletionOnStreamShutdown)
        checkpointAndHandleErrors(shutdownInput.getCheckpointer)
    }

    queue.complete()
    consumerStats.reportShutdown(shardConsumerId, shutdownReason)
    log.info(s"Finished shutting down $shardConsumerId, reason: $shutdownReason.")
  }

  override def shutdownRequested(checkpointer: IRecordProcessorCheckpointer): Unit = {
    shutdown(
      new ShutdownInput()
        .withCheckpointer(checkpointer)
        .withShutdownReason(ShutdownReason.REQUESTED)
    )
  }

  private def blockToEnqueueAndHandleResult(kinesisRecords: IndexedSeq[KinesisRecord]): Unit = {
    try {
      kinesisRecords.foreach(consumerStats.trackRecord(shardConsumerId, _))
      val enqueueFuture = consumerStats.trackBatchEnqueue(shardConsumerId, kinesisRecords.size) {
        queue.offer(kinesisRecords)
      }
      Await.result(enqueueFuture, Duration.Inf) match {
        case QueueOfferResult.Enqueued =>
        // Do nothing.

        case QueueOfferResult.QueueClosed =>
        // Do nothing.

        case QueueOfferResult.Dropped =>
          streamKillSwitch.abort(new AssertionError(
            "RecordProcessor source queue must use `OverflowStrategy.Backpressure`."
          ))

        case QueueOfferResult.Failure(e) =>
          streamKillSwitch.abort(e)
      }
    } catch {
      case NonFatal(e) => streamKillSwitch.abort(e)
    }
  }

  private def checkpointAndHandleErrors(checkpointer: IRecordProcessorCheckpointer, shardEnd: Boolean = false): Unit = {
    try {
      if (shardEnd && shardCheckpointTracker.allInFlightRecordsCompeted) {
        // Checkpointing the actual offset is not enough. Instead, we are required to use the checkpoint()
        // method without arguments, which is not covered by Kinesis documentation.
        checkpointer.checkpoint()
        consumerStats.checkpointShardEndAcked(shardConsumerId)
        log.info(s"Successfully checkpointed $shardConsumerId at SHARD_END.")
      }
      else {
        shardCheckpointTracker.checkpointLastCompletedRecord { kinesisRecord =>
          val seqNumber = kinesisRecord.sequenceNumber
          val subSeqNumber = kinesisRecord.subSequenceNumber.getOrElse(0L)
          checkpointer.checkpoint(seqNumber, subSeqNumber)
          consumerStats.checkpointAcked(shardConsumerId)
          log.info(s"Successfully checkpointed $shardConsumerId at ${kinesisRecord.offsetString}.")
        }
      }
    }
    catch {
      case e: ShutdownException =>
        // Do nothing.

      case e@(_: ThrottlingException | _: KinesisClientLibDependencyException) =>
        consumerStats.checkpointDelayed(shardConsumerId, e)
        log.error(s"Failed to checkpoint $shardConsumerId, will retry later.", e)

      case NonFatal(e) =>
        consumerStats.checkpointFailed(shardConsumerId, e)
        log.error(s"Failed to checkpoint $shardConsumerId, failing the streaming...", e)
        streamKillSwitch.abort(e)
    }
  }

  private def waitForInFlightRecordsOrTermination(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val allCompletedOrTermination = Future.firstCompletedOf(Seq(
      shardCheckpointTracker.allInFlightRecordsCompetedFuture, streamTerminationFuture
    ))
    Try(Await.result(allCompletedOrTermination, Duration.Inf))
  }

  private def waitForInFlightRecordsUnlessStreamFailed(waitDuration: Duration): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val hasStreamFailed = {
      if (streamTerminationFuture.isCompleted) Try(Await.result(streamTerminationFuture, 0.seconds)).isFailure
      else false
    }
    if (!hasStreamFailed) Try(Await.result(shardCheckpointTracker.allInFlightRecordsCompetedFuture, waitDuration))
  }

  private def getOffsetString(n: ExtendedSequenceNumber): String = {
    s"Offset(sequenceNumber=${n.getSequenceNumber}, subSequenceNumber=${n.getSubSequenceNumber})"
  }
}