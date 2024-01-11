// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.io

import scala.annotation.tailrec
import scala.collection.mutable.Queue

import akka.util.ByteString
import org.slf4j.{Logger, LoggerFactory}

import org.alephium.crypto.{Blake2b => Hash}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.validation.BlockValidation
import org.alephium.io.{IOResult, RocksDBKeyValueStorage, SparseMerkleTrie}
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{BlockHash, ChainIndex}
import org.alephium.protocol.vm.ContractStorageImmutableState
import org.alephium.serde.Serde
import org.alephium.util.AVector
import org.alephium.util.BloomFilter

class PruneStorageService(
    storages: Storages
)(implicit
    blockFlow: BlockFlow,
    groupConfig: GroupConfig
) {
  private val logger: Logger         = LoggerFactory.getLogger(this.getClass)
  private val retainedHeight         = 128
  private val bloomNumberOfHashes    = 80000000L
  private val bloomFalsePositiveRate = 0.01

  def prune(): Unit = {
    val bloomFilterResult = buildBloomFilter()
    bloomFilterResult match {
      case Right(bloomFilter) =>
        val (totalCount, pruneCount) = prune(bloomFilter)
        logger.info(s"[FINAL] totalCount: ${totalCount}, pruneCount: ${pruneCount}")
      case Left(error) =>
        logger.info(s"error: ${error}")
    }
  }

  def buildBloomFilter(): IOResult[BloomFilter] = {
    for {
      retainedBlockHashes <- getRetainedBlockHashes()
      _ = assume(
        retainedBlockHashes.length == retainedHeight,
        s"blocks length ${retainedBlockHashes.length} should be equal to $retainedHeight"
      )
      bloomFilter <- retainedBlockHashes
        .foldE(BloomFilter(bloomNumberOfHashes, bloomFalsePositiveRate)) {
          case (bloomFilter, blockHashes) =>
            buildBloomFilter(blockHashes.head, blockHashes.tail, bloomFilter)
        }
    } yield bloomFilter
  }

  def buildBloomFilter(
      baseBlockHash: BlockHash,
      latestBlockHashesToBeApplied: AVector[BlockHash],
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    for {
      bloomFilter0 <- buildBaseBloomFilter(baseBlockHash, bloomFilter)
      bloomFilter  <- applyLatestBlocks(baseBlockHash, latestBlockHashesToBeApplied, bloomFilter0)
    } yield bloomFilter
  }

  private def buildBaseBloomFilter(
      blockHash: BlockHash,
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    val chainIndex = ChainIndex.from(blockHash)
    for {
      persistedWorldState <- storages.worldStateStorage.getPersistedWorldState(blockHash)
      bloomFilter0 <- buildBloomFilterForSMT(
        s"outputState (${chainIndex.prettyString})",
        persistedWorldState.outputState,
        bloomFilter
      )
      bloomFilter1 <- buildBloomFilterForSMT(
        s"contractState (${chainIndex.prettyString})",
        persistedWorldState.contractState,
        bloomFilter0
      )
      bloomFilter <- buildBloomFilterForSMT(
        s"codeState (${chainIndex.prettyString})",
        persistedWorldState.codeState,
        bloomFilter1
      )
    } yield bloomFilter
  }

  private def buildBloomFilterForSMT[K, V](
      label: String,
      smt: SparseMerkleTrie[K, V],
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    buildBloomFilterForSMT(label, smt, Queue(Seq(smt.rootHash)), bloomFilter, 0, 0)
  }

  @tailrec
  private def buildBloomFilterForSMT[K, V](
      label: String,
      smt: SparseMerkleTrie[K, V],
      currentHashesQueue: Queue[Seq[Hash]],
      bloomFilter: BloomFilter,
      totalCount: Int,
      lastLoggingCount: Int
  ): IOResult[BloomFilter] = {
    val updatedLastLoggingCount = if (totalCount - lastLoggingCount > 100000) {
      logger.info(s"[Building Bloom Filter..] $label, current items: ${totalCount}")
      totalCount
    } else {
      lastLoggingCount
    }

    if (currentHashesQueue.nonEmpty) {
      val currentHashes = currentHashesQueue.dequeue()

      currentHashes.map(_.bytes).foreach(bloomFilter.add(_))

      val allNodes = smt.getNodesUnsafe(currentHashes)
      val newHashes: Seq[Hash] = allNodes.flatMap { node =>
        node match {
          case SparseMerkleTrie.BranchNode(_, children) =>
            children.toSeq.flatten
          case SparseMerkleTrie.LeafNode(_, _) =>
            Seq.empty
        }
      }
      newHashes.grouped(256).foreach(currentHashesQueue.enqueue(_))
      buildBloomFilterForSMT(
        label,
        smt,
        currentHashesQueue,
        bloomFilter,
        totalCount + currentHashes.length,
        updatedLastLoggingCount
      )
    } else {
      Right(bloomFilter)
    }
  }

  private def applyLatestBlocks(
      currentBlockHash: BlockHash,
      blockHashesToApply: AVector[BlockHash],
      bloomFilter: BloomFilter
  ): IOResult[BloomFilter] = {
    blockHashesToApply.foldE(bloomFilter) { (bloomFilter, blockHash) =>
      logger.info(s"applying block ${blockHash.toHexString}")

      applyBlock(currentBlockHash, blockHash).map { newKeys =>
        newKeys.map(_.bytes).foreach(bloomFilter.add(_))
        bloomFilter
      }
    }
  }

  private[io] def applyBlock(
      currentBlockHash: BlockHash,
      blockHash: BlockHash
  ): IOResult[AVector[Hash]] = {
    val blockValidation = BlockValidation.build(blockFlow)
    val chainIndex      = ChainIndex.from(currentBlockHash)
    assume(chainIndex.isIntraGroup)
    val blockchain = blockFlow.getBlockChain(chainIndex)

    for {
      block <- blockchain.getBlock(blockHash)
      cachedWorldState <- blockValidation.validate(block, blockFlow) match {
        case Right(Some(cachedWorldState)) =>
          Right(cachedWorldState)
        case Left(Left(error)) =>
          Left(error)
        case _ =>
          throw new Error(s"block ${blockHash.toHexString} is invalid")
      }
      _                 <- blockFlow.updateState(cachedWorldState, block)
      outputStateKeys   <- cachedWorldState.outputState.getNodeKeys()
      contractStateKeys <- cachedWorldState.contractState.getNodeKeys()
      codeStateKeys     <- cachedWorldState.codeState.getNodeKeys()
    } yield outputStateKeys ++ contractStateKeys ++ codeStateKeys
  }

  private def prune(bloomFilter: BloomFilter): (Int, Int) = {
    val trieStorage =
      storages.worldStateStorage.trieStorage.asInstanceOf[RocksDBKeyValueStorage[Hash, Node]]
    var totalCount      = 0
    var pruneCount      = 0
    var batchDeleteKeys = Seq.empty[ByteString]
    val batchDeleteSize = 256

    trieStorage.iterateRaw((key, value) => {
      if (totalCount % 1000000 == 0) {
        logger.info(s"[Pruning..] totalCount: ${totalCount}, pruneCount: ${pruneCount}")
      }

      if (!bloomFilter.mightContain(key) && notImmutableState(value)) {
        pruneCount += 1
        batchDeleteKeys = key +: batchDeleteKeys
      }

      if (batchDeleteKeys.length >= batchDeleteSize) {
        trieStorage.deleteBatchRawUnsafe(batchDeleteKeys)
        batchDeleteKeys = Seq.empty[ByteString]
      }

      totalCount += 1
    })

    if (batchDeleteKeys.nonEmpty) {
      trieStorage.deleteBatchRawUnsafe(batchDeleteKeys)
    }

    (totalCount, pruneCount)
  }

  private def getRetainedBlockHashes(): IOResult[AVector[AVector[BlockHash]]] = {
    for {
      result <- groupConfig.cliqueGroupIndexes.mapE { groupIndex =>
        getRetainBlockHashesForChain(ChainIndex(groupIndex, groupIndex))
      }
    } yield result
  }

  private def getRetainBlockHashesForChain(chainIndex: ChainIndex): IOResult[AVector[BlockHash]] = {
    for {
      bestTip <- blockFlow.getHeaderChain(chainIndex).getBestTip()
      result  <- getRetainBlockHashes(bestTip, 0, AVector(bestTip))
    } yield result
  }

  @tailrec
  private def getRetainBlockHashes(
      blockHash: BlockHash,
      currentHeight: Int,
      allBlockHashes: AVector[BlockHash]
  ): IOResult[AVector[BlockHash]] = {
    if (currentHeight >= retainedHeight - 1) {
      Right(allBlockHashes)
    } else {
      storages.headerStorage.get(blockHash).map(_.parentHash) match {
        case Right(parentHash) =>
          getRetainBlockHashes(
            parentHash,
            currentHeight + 1,
            parentHash +: allBlockHashes
          )
        case Left(err) =>
          Left(err)
      }
    }
  }

  def notImmutableState(value: ByteString): Boolean = {
    implicitly[Serde[ContractStorageImmutableState]].deserialize(value).isLeft
  }
}
