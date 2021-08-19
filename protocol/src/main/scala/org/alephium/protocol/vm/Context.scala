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

package org.alephium.protocol.vm

import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.model._
import org.alephium.util.{discard, AVector, TimeStamp}

final case class BlockEnv(chainId: ChainId, timeStamp: TimeStamp, target: Target)
object BlockEnv {
  def from(header: BlockHeader)(implicit networkConfig: NetworkConfig): BlockEnv =
    BlockEnv(networkConfig.chainId, header.timestamp, header.target)
}

final case class TxEnv(
    tx: TransactionAbstract,
    prevOutputs: AVector[AssetOutput],
    signatures: Stack[Signature]
)

trait StatelessContext extends CostStrategy {
  def blockEnv: BlockEnv
  def txEnv: TxEnv
  def getInitialBalances(): ExeResult[Balances]

  def tx: TransactionAbstract      = txEnv.tx
  def txId: Hash                   = txEnv.tx.id
  def signatures: Stack[Signature] = txEnv.signatures

  def getTxPrevOutput(indexRaw: Val.U256): ExeResult[AssetOutput] = {
    indexRaw.v.toInt.flatMap(txEnv.prevOutputs.get).toRight(Right(InvalidTxInputIndex))
  }

  def getTxCaller(indexRaw: Val.U256): ExeResult[Val.Address] = {
    getTxPrevOutput(indexRaw).map(output => Val.Address(output.lockupScript))
  }
}

object StatelessContext {
  def apply(
      blockEnv: BlockEnv,
      txEnv: TxEnv,
      txGas: GasBox
  ): StatelessContext =
    new Impl(blockEnv, txEnv, txGas)

  final class Impl(
      val blockEnv: BlockEnv,
      val txEnv: TxEnv,
      var gasRemaining: GasBox
  ) extends StatelessContext {
    def getInitialBalances(): ExeResult[Balances] = failed(ExpectNonPayableMethod)
  }
}

trait StatefulContext extends StatelessContext with ContractPool {
  def worldState: WorldState.Staging

  def outputBalances: Balances

  lazy val generatedOutputs: ArrayBuffer[TxOutput]        = ArrayBuffer.empty
  lazy val contractInputs: ArrayBuffer[ContractOutputRef] = ArrayBuffer.empty

  def nextOutputIndex: Int

  def nextContractOutputRef(output: ContractOutput): ContractOutputRef =
    ContractOutputRef.unsafe(txId, output, nextOutputIndex)

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def generateOutput(output: TxOutput): ExeResult[Unit] = {
    output match {
      case contractOutput @ ContractOutput(_, LockupScript.P2C(contractId), _) =>
        val outputRef = nextContractOutputRef(contractOutput)
        generatedOutputs.addOne(output)
        updateContractAsset(contractId, outputRef, contractOutput)
      case _ =>
        generatedOutputs.addOne(output)
        Right(())
    }
  }

  def createContract(
      code: StatefulContract.HalfDecoded,
      initialStateHash: Hash,
      initialBalances: BalancesPerLockup,
      initialFields: AVector[Val]
  ): ExeResult[Unit] = {
    val contractId = TxOutputRef.key(txId, nextOutputIndex)
    val contractOutput = ContractOutput(
      initialBalances.alfAmount,
      LockupScript.p2c(contractId),
      initialBalances.tokenVector
    )
    val outputRef = nextContractOutputRef(contractOutput)
    for {
      _ <- code.check(initialFields)
      _ <-
        worldState
          .createContractUnsafe(code, initialStateHash, initialFields, outputRef, contractOutput)
          .map(_ => discard(generatedOutputs.addOne(contractOutput)))
          .left
          .map(e => Left(IOErrorUpdateState(e)))
    } yield ()
  }

  def useContractAsset(contractId: ContractId): ExeResult[BalancesPerLockup] = {
    for {
      balances <- worldState
        .useContractAsset(contractId)
        .map { case (contractOutputRef, contractAsset) =>
          contractInputs.addOne(contractOutputRef)
          BalancesPerLockup.from(contractAsset)
        }
        .left
        .map(e => Left(IOErrorLoadContract(e)))
      _ <- markAssetInUsing(contractId)
    } yield balances
  }

  def destroyContract(
      contractId: ContractId,
      contractAssets: BalancesPerLockup,
      address: LockupScript
  ): ExeResult[Unit] = {
    for {
      _ <- address match {
        case _: LockupScript.Asset => okay
        case _: LockupScript.P2C   => failed(InvalidAddressTypeInContractDestroy)
      }
      _ <- outputBalances.add(address, contractAssets).toRight(Right(InvalidBalances))
      _ <- removeContract(contractId)
    } yield ()
  }

  def updateContractAsset(
      contractId: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): ExeResult[Unit] = {
    for {
      _ <- worldState
        .updateContract(contractId, outputRef, output)
        .left
        .map(e => Left(IOErrorUpdateState(e)))
      _ <- markAssetFlushed(contractId)
    } yield ()
  }
}

object StatefulContext {
  def apply(
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Cached,
      preOutputs: AVector[AssetOutput]
  ): StatefulContext = {
    val txEnv = TxEnv(tx, preOutputs, Stack.popOnly(tx.contractSignatures))
    new Impl(blockEnv, txEnv, worldState, preOutputs, gasRemaining)
  }

  def build(
      blockEnv: BlockEnv,
      tx: TransactionAbstract,
      gasRemaining: GasBox,
      worldState: WorldState.Cached,
      preOutputsOpt: Option[AVector[AssetOutput]]
  ): ExeResult[StatefulContext] = {
    preOutputsOpt match {
      case Some(outputs) => Right(apply(blockEnv, tx, gasRemaining, worldState, outputs))
      case None =>
        worldState.getPreOutputsForVM(tx) match {
          case Right(Some(outputs)) => Right(apply(blockEnv, tx, gasRemaining, worldState, outputs))
          case Right(None)          => failed(NonExistTxInput)
          case Left(error)          => ioFailed(IOErrorLoadOutputs(error))
        }
    }
  }

  final class Impl(
      val blockEnv: BlockEnv,
      val txEnv: TxEnv,
      val initWorldState: WorldState.Cached,
      val preOutputs: AVector[AssetOutput],
      var gasRemaining: GasBox
  ) extends StatefulContext {
    val worldState: WorldState.Staging = initWorldState.staging()

    def nextOutputIndex: Int = tx.unsigned.fixedOutputs.length + generatedOutputs.length

    /*
     * this should be used only when the tx has passed these checks in validation
     * 1. inputs are not empty
     * 2. gas fee bounds are validated
     */
    @SuppressWarnings(
      Array(
        "org.wartremover.warts.JavaSerializable",
        "org.wartremover.warts.Product",
        "org.wartremover.warts.Serializable"
      )
    )
    def getInitialBalances(): ExeResult[Balances] =
      if (tx.unsigned.scriptOpt.exists(_.entryMethod.isPayable)) {
        for {
          balances <- Balances
            .from(preOutputs, tx.unsigned.fixedOutputs)
            .toRight(Right(InvalidBalances))
          _ <- balances
            .subAlf(preOutputs.head.lockupScript, tx.gasFeeUnsafe)
            .toRight(Right(UnableToPayGasFee))
        } yield balances
      } else {
        failed(ExpectNonPayableMethod)
      }

    val outputBalances: Balances = Balances.empty
  }
}
