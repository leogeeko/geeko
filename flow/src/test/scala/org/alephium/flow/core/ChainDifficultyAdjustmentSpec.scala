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

package org.alephium.flow.core

import java.math.BigInteger

import scala.collection.mutable
import scala.util.Random

import org.scalatest.Assertion

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.IOResult
import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.model.Target
import org.alephium.util.{AVector, NumericHelpers, TimeStamp}

class ChainDifficultyAdjustmentSpec extends AlephiumFlowSpec { Test =>
  trait MockFixture extends ChainDifficultyAdjustment with NumericHelpers {
    implicit val consensusConfig: ConsensusSetting = ConsensusSetting(18, 100, 25)

    val chainInfo = mutable.HashMap.empty[Hash, (Int, TimeStamp)] // block hash -> (height, timestamp)
    val threshold = consensusConfig.powAveragingWindow + 1

    def getHeight(hash: Hash): IOResult[Int] = Right(chainInfo(hash)._1)

    def getHash(height: Int): Hash = chainInfo.filter(_._2._1 equals height).head._1

    def getTimestamp(hash: Hash): IOResult[TimeStamp] = Right(chainInfo(hash)._2)

    def chainBack(hash: Hash, heightUntil: Int): IOResult[AVector[Hash]] = {
      val maxHeight: Int = getHeight(hash).rightValue
      val hashes = AVector
        .from(chainInfo.filter {
          case (_, (height, _)) => height > heightUntil && height <= maxHeight
        }.keys)
        .sortBy(getHeight(_).rightValue)
      Right(hashes)
    }

    def setup(data: AVector[(Hash, TimeStamp)]): Unit = {
      assume(chainInfo.isEmpty)
      data.foreachWithIndex {
        case ((hash, timestamp), height) => chainInfo(hash) = height -> timestamp
      }
    }

    def calMedianBlockTime(hash: Hash): IOResult[(TimeStamp, TimeStamp)] = {
      calMedianBlockTime(hash, getHeight(hash).rightValue)
    }
  }

  it should "calculate target correctly" in new MockFixture {
    val currentTarget = Target.unsafe((consensusConfig.maxMiningTarget / 4).underlying())
    reTarget(currentTarget, consensusConfig.expectedWindowTimeSpan.millis) is currentTarget
    reTarget(currentTarget, (consensusConfig.expectedWindowTimeSpan timesUnsafe 2).millis).value is
      (currentTarget * 2).underlying()
    reTarget(currentTarget, (consensusConfig.expectedWindowTimeSpan divUnsafe 2).millis) is
      Target.unsafe((currentTarget.value / 2).underlying())
  }

  it should "compute the correct median value" in {
    import ChainDifficultyAdjustment.calMedian

    def checkCalMedian(tss: AVector[Long], expected1: Long, expected2: Long): Assertion = {
      val expected = (TimeStamp.unsafe(expected1), TimeStamp.unsafe(expected2))
      calMedian(tss.map(TimeStamp.unsafe), 7) is expected
    }

    checkCalMedian(AVector(0, 1, 2, 3, 4, 5, 6, 7), 4, 3)
    checkCalMedian(AVector(7, 6, 5, 4, 3, 2, 1, 0), 3, 4)
  }

  it should "calculate correct median block time" in new MockFixture {
    val genesisTs = TimeStamp.now()
    val data = AVector.tabulate(threshold + 1) { height =>
      Hash.random -> (genesisTs + consensusConfig.expectedTimeSpan.timesUnsafe(height.toLong))
    }
    setup(data)

    val median1 = data(18)._2
    val median2 = data(1)._2
    calMedianBlockTime(data.last._1) isE (median1 -> median2)
  }

  it should "return initial target when few blocks" in {
    val maxHeight = ALF.GenesisHeight + consensusConfig.powAveragingWindow + 1
    (1 until maxHeight).foreach { n =>
      val data       = AVector.fill(n)(Hash.random -> TimeStamp.zero)
      val fixture    = new MockFixture { setup(data) }
      val latestHash = data.last._1
      fixture.chainBack(latestHash, 0) isE data.tail.map(_._1)
      val currentTarget = Target.unsafe(BigInteger.valueOf(Random.nextLong(Long.MaxValue)))
      fixture.calHashTarget(latestHash, currentTarget) isE currentTarget
    }
  }

  it should "return the same target when block times are exact" in new MockFixture {
    val genesisTs = TimeStamp.now()
    val data = AVector.tabulate(2 * threshold) { height =>
      Hash.random -> (genesisTs + consensusConfig.expectedTimeSpan.timesUnsafe(height.toLong))
    }
    setup(data)

    (threshold until 2 * threshold).foreach { height =>
      val hash          = getHash(height)
      val currentTarget = Target.unsafe(BigInteger.valueOf(Random.nextLong(Long.MaxValue)))
      calMedianBlockTime(hash, height) isE (data(height)._2 -> data(height - 17)._2)
      calHashTarget(hash, currentTarget) isE currentTarget
    }
  }

  it should "decrease the target when blocktime keep increasing" in new MockFixture {
    var currentTs = TimeStamp.now()
    var ratio     = 1.0
    val data = AVector.tabulate(2 * threshold) { _ =>
      ratio = ratio * 1.2
      val delta = (consensusConfig.expectedTimeSpan.millis * ratio).toLong
      currentTs = currentTs.plusMillisUnsafe(delta)
      Hash.random -> currentTs
    }
    setup(data)

    (threshold until 2 * threshold).foreach { height =>
      val hash          = getHash(height)
      val currentTarget = Target.unsafe(BigInteger.valueOf(1024))
      calMedianBlockTime(hash, height) isE (data(height)._2 -> data(height - 17)._2)
      calHashTarget(hash, currentTarget) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMax.millis)
    }
  }

  it should "increase the target when blocktime keep decreasing" in new MockFixture {
    var currentTs = TimeStamp.now()
    var ratio     = 1.0
    val data = AVector.tabulate(2 * threshold) { _ =>
      ratio = ratio * 1.2
      val delta = (consensusConfig.expectedTimeSpan.millis / ratio).toLong
      currentTs = currentTs.plusMillisUnsafe(delta)
      Hash.random -> currentTs
    }
    setup(data)

    (threshold until 2 * threshold).foreach { height =>
      val hash          = getHash(height)
      val currentTarget = Target.unsafe(BigInteger.valueOf(1024))
      calMedianBlockTime(hash, height) isE (data(height)._2 -> data(height - 17)._2)
      calHashTarget(hash, currentTarget) isE
        reTarget(currentTarget, consensusConfig.windowTimeSpanMin.millis)
    }
  }

  trait SimulationFixture extends MockFixture {
    var currentTs = TimeStamp.now()
    val data = AVector.tabulate(2 * threshold) { _ =>
      currentTs = currentTs + consensusConfig.expectedTimeSpan
      Hash.random -> currentTs
    }
    setup(data)

    var currentHeight = chainInfo.values.map(_._1).max
    def addNew(hash: Hash, timestamp: TimeStamp) = {
      currentHeight += 1
      currentTs = timestamp
      chainInfo += hash -> (currentHeight -> timestamp)
    }

    val initialTarget =
      Target.unsafe(consensusConfig.maxMiningTarget.value.divide(BigInteger.valueOf(128)))
    var currentTarget = calHashTarget(getHash(currentHeight), initialTarget).rightValue
    currentTarget is initialTarget
    def stepSimulation(finalTarget: Target) = {
      val ratio =
        (BigDecimal(finalTarget.value) / BigDecimal(currentTarget.value)).toDouble
      val error    = (Random.nextDouble() - 0.5) / 20
      val duration = consensusConfig.expectedTimeSpan.millis * ratio * (1 + error)
      val nextTs   = currentTs.plusMillisUnsafe(duration.toLong)
      val newHash  = Hash.random
      addNew(newHash, nextTs)
      currentTarget = calHashTarget(newHash, currentTarget).rightValue
    }

    def checkRatio(ratio: Double, expected: Double) = {
      ratio >= expected * 0.95 && ratio <= expected * 1.05
    }
  }

  it should "simulate hashrate increasing" in new SimulationFixture {
    val finalTarget = Target.unsafe(initialTarget.value.divide(BigInteger.valueOf(100)))
    (0 until 1000).foreach { _ =>
      stepSimulation(finalTarget)
    }
    val ratio = BigDecimal(initialTarget.value) / BigDecimal(currentTarget.value)
    checkRatio(ratio.toDouble, 100.0) is true
  }

  it should "simulate hashrate decreasing" in new SimulationFixture {
    val finalTarget = Target.unsafe(initialTarget.value.multiply(BigInteger.valueOf(100)))
    (0 until 1000).foreach { _ =>
      stepSimulation(finalTarget)
    }
    val ratio = BigDecimal(currentTarget.value) / BigDecimal(initialTarget.value)
    checkRatio(ratio.toDouble, 100.0) is true
  }
}
