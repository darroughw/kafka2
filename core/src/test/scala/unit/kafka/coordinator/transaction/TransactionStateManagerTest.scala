/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.coordinator.transaction

import java.nio.ByteBuffer

import kafka.log.Log
import kafka.server.{FetchDataInfo, LogOffsetMetadata, ReplicaManager}
import kafka.utils.{MockScheduler, Pool, ZkUtils}
import kafka.utils.TestUtils.fail
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.internals.Topic.{GROUP_METADATA_TOPIC_NAME, TRANSACTION_STATE_TOPIC_NAME}
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.IsolationLevel
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse
import org.apache.kafka.common.requests.TransactionResult
import org.apache.kafka.common.utils.MockTime
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.{After, Before, Test}
import org.easymock.{Capture, EasyMock, IAnswer}

import scala.collection.Map
import scala.collection.mutable
import scala.collection.JavaConverters._

class TransactionStateManagerTest {

  val partitionId = 0
  val numPartitions = 2
  val transactionTimeoutMs: Int = 1000
  val topicPartition = new TopicPartition(TRANSACTION_STATE_TOPIC_NAME, partitionId)
  val coordinatorEpoch = 10

  val txnRecords: mutable.ArrayBuffer[SimpleRecord] = mutable.ArrayBuffer[SimpleRecord]()

  val time = new MockTime()
  val scheduler = new MockScheduler(time)
  val zkUtils: ZkUtils = EasyMock.createNiceMock(classOf[ZkUtils])
  val replicaManager: ReplicaManager = EasyMock.createNiceMock(classOf[ReplicaManager])

  EasyMock.expect(zkUtils.getTopicPartitionCount(TRANSACTION_STATE_TOPIC_NAME))
    .andReturn(Some(numPartitions))
    .anyTimes()

  EasyMock.replay(zkUtils)

  val txnConfig = TransactionConfig()
  val transactionManager: TransactionStateManager = new TransactionStateManager(0, zkUtils, scheduler, replicaManager, txnConfig, time)

  val txnId1: String = "one"
  val txnId2: String = "two"
  val txnMessageKeyBytes1: Array[Byte] = TransactionLog.keyToBytes(txnId1)
  val txnMessageKeyBytes2: Array[Byte] = TransactionLog.keyToBytes(txnId2)
  val pidMappings: Map[String, Long] = Map[String, Long](txnId1 -> 1L, txnId2 -> 2L)
  var txnMetadata1: TransactionMetadata = TransactionMetadata(pidMappings(txnId1), 1, transactionTimeoutMs, 0)
  var txnMetadata2: TransactionMetadata = TransactionMetadata(pidMappings(txnId2), 1, transactionTimeoutMs, 0)

  var expectedError: Errors = Errors.NONE

  @Before
  def setUp() {
    // make sure the transactional id hashes to the assigning partition id
    assertEquals(partitionId, transactionManager.partitionFor(txnId1))
    assertEquals(partitionId, transactionManager.partitionFor(txnId2))
  }

  @After
  def tearDown() {
    EasyMock.reset(zkUtils, replicaManager)
    transactionManager.shutdown()
  }

  @Test
  def testValidateTransactionTimeout() {
    assertTrue(transactionManager.validateTransactionTimeoutMs(1))
    assertFalse(transactionManager.validateTransactionTimeoutMs(-1))
    assertFalse(transactionManager.validateTransactionTimeoutMs(0))
    assertTrue(transactionManager.validateTransactionTimeoutMs(txnConfig.transactionMaxTimeoutMs))
    assertFalse(transactionManager.validateTransactionTimeoutMs(txnConfig.transactionMaxTimeoutMs + 1))
  }

  @Test
  def testAddGetPids() {
    transactionManager.addLoadedTransactionsToCache(partitionId, coordinatorEpoch, new Pool[String, TransactionMetadata]())

    assertEquals(None, transactionManager.getTransactionState(txnId1))
    assertEquals(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1), transactionManager.addTransaction(txnId1, txnMetadata1))
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))
    assertEquals(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1), transactionManager.addTransaction(txnId1, txnMetadata2))
  }

  @Test
  def testLoadAndRemoveTransactionsForPartition() {
    // generate transaction log messages for two pids traces:

    // pid1's transaction started with two partitions
    txnMetadata1.state = Ongoing
    txnMetadata1.addPartitions(Set[TopicPartition](new TopicPartition("topic1", 0),
      new TopicPartition("topic1", 1)))

    txnRecords += new SimpleRecord(txnMessageKeyBytes1, TransactionLog.valueToBytes(txnMetadata1.prepareNoTransit()))

    // pid1's transaction adds three more partitions
    txnMetadata1.addPartitions(Set[TopicPartition](new TopicPartition("topic2", 0),
      new TopicPartition("topic2", 1),
      new TopicPartition("topic2", 2)))

    txnRecords += new SimpleRecord(txnMessageKeyBytes1, TransactionLog.valueToBytes(txnMetadata1.prepareNoTransit()))

    // pid1's transaction is preparing to commit
    txnMetadata1.state = PrepareCommit

    txnRecords += new SimpleRecord(txnMessageKeyBytes1, TransactionLog.valueToBytes(txnMetadata1.prepareNoTransit()))

    // pid2's transaction started with three partitions
    txnMetadata2.state = Ongoing
    txnMetadata2.addPartitions(Set[TopicPartition](new TopicPartition("topic3", 0),
      new TopicPartition("topic3", 1),
      new TopicPartition("topic3", 2)))

    txnRecords += new SimpleRecord(txnMessageKeyBytes2, TransactionLog.valueToBytes(txnMetadata2.prepareNoTransit()))

    // pid2's transaction is preparing to abort
    txnMetadata2.state = PrepareAbort

    txnRecords += new SimpleRecord(txnMessageKeyBytes2, TransactionLog.valueToBytes(txnMetadata2.prepareNoTransit()))

    // pid2's transaction has aborted
    txnMetadata2.state = CompleteAbort

    txnRecords += new SimpleRecord(txnMessageKeyBytes2, TransactionLog.valueToBytes(txnMetadata2.prepareNoTransit()))

    // pid2's epoch has advanced, with no ongoing transaction yet
    txnMetadata2.state = Empty
    txnMetadata2.topicPartitions.clear()

    txnRecords += new SimpleRecord(txnMessageKeyBytes2, TransactionLog.valueToBytes(txnMetadata2.prepareNoTransit()))

    val startOffset = 15L   // it should work for any start offset
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE, txnRecords: _*)

    prepareTxnLog(topicPartition, startOffset, records)

    // this partition should not be part of the owned partitions
    assertFalse(transactionManager.isCoordinatorFor(txnId1))
    assertFalse(transactionManager.isCoordinatorFor(txnId2))

    transactionManager.loadTransactionsForTxnTopicPartition(partitionId, 0, (_, _, _, _, _) => ())

    // let the time advance to trigger the background thread loading
    scheduler.tick()

    val cachedPidMetadata1 = transactionManager.getTransactionState(txnId1).getOrElse(fail(txnId1 + "'s transaction state was not loaded into the cache"))
    val cachedPidMetadata2 = transactionManager.getTransactionState(txnId2).getOrElse(fail(txnId2 + "'s transaction state was not loaded into the cache"))

    // they should be equal to the latest status of the transaction
    assertEquals(txnMetadata1, cachedPidMetadata1.transactionMetadata)
    assertEquals(txnMetadata2, cachedPidMetadata2.transactionMetadata)

    // this partition should now be part of the owned partitions
    assertTrue(transactionManager.isCoordinatorFor(txnId1))
    assertTrue(transactionManager.isCoordinatorFor(txnId2))

    transactionManager.removeTransactionsForTxnTopicPartition(partitionId, coordinatorEpoch)

    // let the time advance to trigger the background thread removing
    scheduler.tick()

    assertFalse(transactionManager.isCoordinatorFor(txnId1))
    assertFalse(transactionManager.isCoordinatorFor(txnId2))

    assertEquals(None, transactionManager.getTransactionState(txnId1))
    assertEquals(None, transactionManager.getTransactionState(txnId2))
  }

  @Test
  def testAppendTransactionToLog() {
    transactionManager.addLoadedTransactionsToCache(partitionId, coordinatorEpoch, new Pool[String, TransactionMetadata]())

    // first insert the initial transaction metadata
    transactionManager.addTransaction(txnId1, txnMetadata1)

    prepareForTxnMessageAppend(Errors.NONE)
    expectedError = Errors.NONE

    // update the metadata to ongoing with two partitions
    val newMetadata = txnMetadata1.prepareAddPartitions(Set[TopicPartition](new TopicPartition("topic1", 0),
      new TopicPartition("topic1", 1)), time.milliseconds())

    // append the new metadata into log
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch, newMetadata, assertCallback)

    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    // append to log again with expected failures
    val failedMetadata = txnMetadata1.prepareAddPartitions(Set[TopicPartition](new TopicPartition("topic2", 0)), time.milliseconds())

    // test COORDINATOR_NOT_AVAILABLE cases
    expectedError = Errors.COORDINATOR_NOT_AVAILABLE

    prepareForTxnMessageAppend(Errors.UNKNOWN_TOPIC_OR_PARTITION)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    prepareForTxnMessageAppend(Errors.NOT_ENOUGH_REPLICAS)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    prepareForTxnMessageAppend(Errors.NOT_ENOUGH_REPLICAS_AFTER_APPEND)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    prepareForTxnMessageAppend(Errors.REQUEST_TIMED_OUT)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    // test NOT_COORDINATOR cases
    expectedError = Errors.NOT_COORDINATOR

    prepareForTxnMessageAppend(Errors.NOT_LEADER_FOR_PARTITION)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    // test NOT_COORDINATOR cases
    expectedError = Errors.UNKNOWN

    prepareForTxnMessageAppend(Errors.MESSAGE_TOO_LARGE)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))

    prepareForTxnMessageAppend(Errors.RECORD_LIST_TOO_LARGE)
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, failedMetadata, assertCallback)
    assertEquals(Some(CoordinatorEpochAndTxnMetadata(coordinatorEpoch, txnMetadata1)), transactionManager.getTransactionState(txnId1))
  }

  @Test
  def testAppendTransactionToLogWhileProducerFenced() = {
    transactionManager.addLoadedTransactionsToCache(partitionId, 0, new Pool[String, TransactionMetadata]())

    // first insert the initial transaction metadata
    transactionManager.addTransaction(txnId1, txnMetadata1)

    prepareForTxnMessageAppend(Errors.NONE)
    expectedError = Errors.NOT_COORDINATOR

    val newMetadata = txnMetadata1.prepareAddPartitions(Set[TopicPartition](new TopicPartition("topic1", 0),
      new TopicPartition("topic1", 1)), time.milliseconds())

    // modify the cache while trying to append the new metadata
    txnMetadata1.producerEpoch = (txnMetadata1.producerEpoch + 1).toShort

    // append the new metadata into log
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, newMetadata, assertCallback)
  }

  @Test(expected = classOf[IllegalStateException])
  def testAppendTransactionToLogWhilePendingStateChanged() = {
    // first insert the initial transaction metadata
    transactionManager.addTransaction(txnId1, txnMetadata1)

    prepareForTxnMessageAppend(Errors.NONE)
    expectedError = Errors.INVALID_PRODUCER_EPOCH

    val newMetadata = txnMetadata1.prepareAddPartitions(Set[TopicPartition](new TopicPartition("topic1", 0),
      new TopicPartition("topic1", 1)), time.milliseconds())

    // modify the cache while trying to append the new metadata
    txnMetadata1.pendingState = None

    // append the new metadata into log
    transactionManager.appendTransactionToLog(txnId1, coordinatorEpoch = 10, newMetadata, assertCallback)
  }

  @Test
  def shouldReturnNoneIfTransactionIdPartitionNotOwned(): Unit = {
    assertEquals(None, transactionManager.getTransactionState(txnId1))
  }

  @Test
  def shouldOnlyConsiderTransactionsInTheOngoingStateForExpiry(): Unit = {
    for (partitionId <- 0 until numPartitions) {
      transactionManager.addLoadedTransactionsToCache(partitionId, 0, new Pool[String, TransactionMetadata]())
    }

    txnMetadata1.state = Ongoing
    txnMetadata1.txnStartTimestamp = time.milliseconds()
    transactionManager.addTransaction(txnId1, txnMetadata1)
    transactionManager.addTransaction(txnId2, txnMetadata2)

    val ongoingButNotExpiring = txnMetadata1.copy()
    ongoingButNotExpiring.txnTimeoutMs = 10000
    transactionManager.addTransaction("not-expiring", ongoingButNotExpiring)

    val prepareCommit = txnMetadata1.copy()
    prepareCommit.state = PrepareCommit
    transactionManager.addTransaction("pc", prepareCommit)

    val prepareAbort = txnMetadata1.copy()
    prepareAbort.state = PrepareAbort
    transactionManager.addTransaction("pa", prepareAbort)

    val committed = txnMetadata1.copy()
    committed.state = CompleteCommit
    transactionManager.addTransaction("cc", committed)

    val aborted = txnMetadata1.copy()
    aborted.state = CompleteAbort
    transactionManager.addTransaction("ca", aborted)

    time.sleep(2000)
    val expiring = transactionManager.transactionsToExpire()
    assertEquals(List(TransactionalIdAndProducerIdEpoch(txnId1, txnMetadata1.producerId, txnMetadata1.producerEpoch)), expiring)
  }

  @Test
  def shouldWriteTxnMarkersForTransactionInPreparedCommitState(): Unit = {
    verifyWritesTxnMarkersInPrepareState(PrepareCommit)
  }

  @Test
  def shouldWriteTxnMarkersForTransactionInPreparedAbortState(): Unit = {
    verifyWritesTxnMarkersInPrepareState(PrepareAbort)
  }

  private def verifyWritesTxnMarkersInPrepareState(state: TransactionState): Unit = {
    txnMetadata1.state = state
    txnMetadata1.addPartitions(Set[TopicPartition](new TopicPartition("topic1", 0),
      new TopicPartition("topic1", 1)))

    txnRecords += new SimpleRecord(txnMessageKeyBytes1, TransactionLog.valueToBytes(txnMetadata1.prepareNoTransit()))
    val startOffset = 0L
    val records = MemoryRecords.withRecords(startOffset, CompressionType.NONE, txnRecords: _*)

    prepareTxnLog(topicPartition, 0, records)

    var txnId: String = null
    def rememberTxnMarkers(transactionalId: String,
                           coordinatorEpoch: Int,
                           command: TransactionResult,
                           metadata: TransactionMetadata,
                           newMetadata: TransactionMetadataTransition): Unit = {
      txnId = transactionalId
    }

    transactionManager.loadTransactionsForTxnTopicPartition(partitionId, 0, rememberTxnMarkers)
    scheduler.tick()

    assertEquals(txnId1, txnId)
  }

  private def assertCallback(error: Errors): Unit = {
    assertEquals(expectedError, error)
  }

  private def prepareTxnLog(topicPartition: TopicPartition,
                            startOffset: Long,
                            records: MemoryRecords): Unit = {
    EasyMock.reset(replicaManager)

    val logMock =  EasyMock.mock(classOf[Log])
    val fileRecordsMock = EasyMock.mock(classOf[FileRecords])

    val endOffset = startOffset + records.records.asScala.size

    EasyMock.expect(replicaManager.getLog(topicPartition)).andStubReturn(Some(logMock))
    EasyMock.expect(replicaManager.getLogEndOffset(topicPartition)).andStubReturn(Some(endOffset))

    EasyMock.expect(logMock.logStartOffset).andStubReturn(startOffset)
    EasyMock.expect(logMock.read(EasyMock.eq(startOffset), EasyMock.anyInt(), EasyMock.eq(None), EasyMock.eq(true),
      EasyMock.eq(IsolationLevel.READ_UNCOMMITTED)))
      .andReturn(FetchDataInfo(LogOffsetMetadata(startOffset), fileRecordsMock))
    EasyMock.expect(fileRecordsMock.readInto(EasyMock.anyObject(classOf[ByteBuffer]), EasyMock.anyInt()))
      .andReturn(records.buffer)

    EasyMock.replay(logMock, fileRecordsMock, replicaManager)
  }

  private def prepareForTxnMessageAppend(error: Errors): Unit = {
    EasyMock.reset(replicaManager)

    val capturedArgument: Capture[Map[TopicPartition, PartitionResponse] => Unit] = EasyMock.newCapture()
    EasyMock.expect(replicaManager.appendRecords(EasyMock.anyLong(),
      EasyMock.anyShort(),
      internalTopicsAllowed = EasyMock.eq(true),
      isFromClient = EasyMock.eq(false),
      EasyMock.anyObject().asInstanceOf[Map[TopicPartition, MemoryRecords]],
      EasyMock.capture(capturedArgument)))
      .andAnswer(new IAnswer[Unit] {
        override def answer(): Unit = capturedArgument.getValue.apply(
          Map(new TopicPartition(TRANSACTION_STATE_TOPIC_NAME, partitionId) ->
            new PartitionResponse(error, 0L, RecordBatch.NO_TIMESTAMP)
          )
        )
      }
      )
    EasyMock.expect(replicaManager.getMagic(EasyMock.anyObject()))
      .andStubReturn(Some(RecordBatch.MAGIC_VALUE_V1))

    EasyMock.replay(replicaManager)
  }
}
