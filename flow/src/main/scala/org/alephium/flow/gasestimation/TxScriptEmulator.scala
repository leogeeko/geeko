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

package org.alephium.flow.gasestimation

import org.alephium.flow.core._
import org.alephium.flow.core.UtxoSelectionAlgo.TxInputWithAsset
import org.alephium.protocol.Signature
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.StatefulVM.TxScriptExecution
import org.alephium.util._

final case class TxScriptEmulationResult(gasUsed: GasBox, value: TxScriptExecution)

trait TxScriptEmulator {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def emulate(
      inputWithAssets: AVector[TxInputWithAsset],
      fixedOutputs: AVector[AssetOutput],
      script: StatefulScript,
      gasAmountOpt: Option[GasBox] = None,
      gasPriceOpt: Option[GasPrice] = None
  ): Either[String, TxScriptEmulationResult]
}

object TxScriptEmulator {

  // scalastyle:off method.length
  final case class Default(
      flow: BlockFlow
  )(implicit
      networkConfig: NetworkConfig,
      config: GroupConfig,
      logConfig: LogConfig
  ) extends TxScriptEmulator {

    def emulate(
        inputWithAssets: AVector[TxInputWithAsset],
        fixedOutputs: AVector[AssetOutput],
        script: StatefulScript,
        gasAmountOpt: Option[GasBox],
        gasPriceOpt: Option[GasPrice]
    ): Either[String, TxScriptEmulationResult] = {
      assume(inputWithAssets.nonEmpty)
      val groupIndex      = inputWithAssets.head.input.fromGroup
      val chainIndex      = ChainIndex(groupIndex, groupIndex)
      val maximalGasPerTx = getMaximalGasPerTx()

      def runScript(
          blockEnv: BlockEnv,
          groupView: BlockFlowGroupView[WorldState.Cached],
          preOutputs: AVector[AssetOutput]
      ): Either[String, TxScriptExecution] = {
        val gasAmount = gasAmountOpt.getOrElse(minimalGas)
        val gasPrice  = gasPriceOpt.getOrElse(nonCoinbaseMinGasPrice)
        val txTemplate = TransactionTemplate(
          UnsignedTransaction(
            Some(script),
            gasAmount,
            gasPrice,
            inputWithAssets.map(_.input),
            fixedOutputs
          ),
          inputSignatures = AVector.fill(16)(Signature.generate),
          scriptSignatures = AVector.fill(16)(Signature.generate)
        )

        val result =
          VM.checkCodeSize(maximalGasPerTx, script.bytes, blockEnv.getHardFork()).flatMap {
            remainingGas =>
              StatefulVM.runTxScriptMockup(
                groupView.worldState.staging(),
                blockEnv,
                txTemplate,
                preOutputs,
                script.mockup(),
                remainingGas
              )
          }

        result.left.map {
          case Right(error) =>
            s"Execution error when emulating tx script or contract: $error"
          case Left(error) =>
            s"IO error when emulating tx script or contract: $error"
        }
      }

      for {
        blockEnv  <- flow.getDryrunBlockEnv(chainIndex).left.map(_.toString())
        groupView <- flow.getMutableGroupViewIncludePool(chainIndex.from).left.map(_.toString())
        preOutputs = inputWithAssets.map(_.asset.output)
        result <- runScript(blockEnv, groupView, preOutputs)
      } yield TxScriptEmulationResult(
        maximalGasPerTx.subUnsafe(result.gasBox),
        result
      )
    }
  }
  // scalastyle:on method.length

  object Mock extends TxScriptEmulator {
    def emulate(
        inputWithAssets: AVector[TxInputWithAsset],
        fixedOutputs: AVector[AssetOutput],
        script: StatefulScript,
        gasAmountOpt: Option[GasBox],
        gasFeeOpt: Option[GasPrice]
    ): Either[String, TxScriptEmulationResult] = {
      Right(
        TxScriptEmulationResult(
          defaultGasPerInput,
          TxScriptExecution(
            defaultGasPerInput,
            AVector.empty,
            AVector.empty,
            AVector.empty
          )
        )
      )
    }
  }

  object NotImplemented extends TxScriptEmulator {
    def emulate(
        inputWithAssets: AVector[TxInputWithAsset],
        fixedOutputs: AVector[AssetOutput],
        script: StatefulScript,
        gasAmountOpt: Option[GasBox],
        gasFeeOpt: Option[GasPrice]
    ): Either[String, TxScriptEmulationResult] = {
      throw new NotImplementedError("TxScriptEmulator not implemented")
    }
  }
}
